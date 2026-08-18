package ru.fedukhin.edt.mcp.core.internal.http;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import ru.fedukhin.edt.mcp.core.internal.preferences.McpPreferences;
import ru.fedukhin.edt.mcp.core.internal.protocol.McpServerLifecycle;

public class McpHttpService {

    /**
     * Connector socket idle timeout. Jetty's default is 30s — short enough that
     * ordinary pauses in interactive MCP use let it close the long-lived SSE
     * socket, after which the client's next fetch hits a dead connection
     * ("fetch failed"). The SDK keep-alive (see {@code McpServerLifecycle})
     * keeps the socket warm in normal operation; this generous timeout is the
     * backstop for a stalled JVM (GC, debugger) while still reclaiming
     * genuinely dead sockets.
     */
    private static final long CONNECTOR_IDLE_TIMEOUT_MS = 300_000L;

    private final McpServerLifecycle lifecycle;
    private final BearerAuthFilter authFilter;
    private final McpPreferences prefs;

    private Server server;
    private int activePort;
    private ru.fedukhin.edt.mcp.core.ipc.InstanceBeacon beacon;

    public McpHttpService(McpServerLifecycle lifecycle, BearerAuthFilter authFilter,
                          McpPreferences prefs) {
        this.lifecycle = lifecycle;
        this.authFilter = authFilter;
        this.prefs = prefs;
    }

    /**
     * Занимает первый свободный порт диапазона. Раньше порт был один: вторая инстанция
     * EDT на той же машине падала {@code BindException} и оставалась вовсе без
     * MCP-сервера — состояние уходило в {@code ServerState.error} и висело там до
     * ручного вмешательства.
     */
    public synchronized void start() throws Exception {
        if (server != null && server.isStarted()) return;
        int from = prefs.getPort();
        int to = prefs.getPortRangeEnd();
        String bindHost = prefs.getBindHost();

        Exception last = null;
        for (int port = from; port <= to; port++) {
            try {
                startOn(bindHost, port);
                return;
            } catch (Exception e) {
                stopQuietly();
                if (!isAddressInUse(e)) throw e;
                last = e;
            }
        }
        throw new IllegalStateException(
            "MCP server: все порты диапазона " + from + ".." + to + " заняты", last);
    }

    private void startOn(String bindHost, int port) throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(bindHost);
        connector.setPort(port);
        connector.setIdleTimeout(CONNECTOR_IDLE_TIMEOUT_MS);
        server.addConnector(connector);

        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(new AuthGateServlet(lifecycle.buildTransport(), authFilter)),
                       "/mcp/*");
        server.setHandler(ctx);

        server.start();
        // Порт спрашиваем у коннектора: только он знает, на что реально сели.
        activePort = connector.getLocalPort();
        publishBeacon();
    }

    /**
     * Маячок реестра инстанций — вспомогательный механизм: его отказ не должен
     * мешать серверу работать, поэтому исключения только логируются.
     */
    private void publishBeacon() {
        try {
            McpServerLifecycle.WorkspaceIdentity id = lifecycle.workspaceIdentity();
            String path = (id == null || id.path() == null) ? "(unknown)" : id.path();
            java.nio.file.Path leaf = null;
            try {
                leaf = java.nio.file.Path.of(path).getFileName();
            } catch (RuntimeException ignored) { /* путь-заглушка */ }
            String host = prefs.getBindHost();
            beacon = ru.fedukhin.edt.mcp.core.ipc.InstanceBeacon.publish(
                new ru.fedukhin.edt.mcp.core.ipc.InstanceRecord(
                    ProcessHandle.current().pid(),
                    activePort,
                    host,
                    "http://" + host + ":" + activePort + "/mcp/sse",
                    path,
                    leaf == null ? path : leaf.toString(),
                    (id == null || id.projects() == null) ? java.util.List.of() : id.projects(),
                    lifecycle.serverVersion(),
                    java.time.Instant.now().toString()));
        } catch (Exception e) {
            org.eclipse.core.runtime.Platform.getLog(getClass()).log(
                org.eclipse.core.runtime.Status.warning("не удалось опубликовать маячок инстанции", e));
        }
    }

    /** Идемпотентно: зовётся и из stop(), и из провалившейся попытки биндинга. */
    private void closeBeacon() {
        if (beacon == null) return;
        beacon.close();
        beacon = null;
    }

    /**
     * Провалившаяся попытка оставляет не-null и неостановленный {@code Server} с
     * полуоткрытым коннектором — его надо погасить до следующей попытки, иначе
     * он утекает, а guard в {@code start()} пропустит следующий запуск.
     */
    private void stopQuietly() {
        closeBeacon();
        if (server == null) return;
        try { server.stop(); } catch (Exception ignored) { /* best-effort */ }
        server = null;
    }

    /** BindException приходит завёрнутым, поэтому ищем по всей цепочке причин. */
    private static boolean isAddressInUse(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof java.net.BindException) return true;
        }
        return false;
    }

    public synchronized void stop() throws Exception {
        // Маячок снимаем первым: пока сервер гасится, он уже не обслуживает клиентов,
        // и запись о живой инстанции была бы ложью.
        closeBeacon();
        if (server == null) return;
        try {
            lifecycle.close();
            server.stop();
            server.join();
        } finally {
            // Даже если stop/join упали, держаться за мёртвый Server нельзя: isStarted() врал бы,
            // а следующий start() отказывался бы поднимать сервер до рестарта EDT.
            server = null;
        }
    }

    public boolean isStarted() { return server != null && server.isStarted(); }
    public int getPort() { return activePort; }
}
