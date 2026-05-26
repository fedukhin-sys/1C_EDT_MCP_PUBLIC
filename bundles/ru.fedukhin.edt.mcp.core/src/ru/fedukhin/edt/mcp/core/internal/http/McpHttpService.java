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

    public McpHttpService(McpServerLifecycle lifecycle, BearerAuthFilter authFilter,
                          McpPreferences prefs) {
        this.lifecycle = lifecycle;
        this.authFilter = authFilter;
        this.prefs = prefs;
    }

    public synchronized void start() throws Exception {
        if (server != null && server.isStarted()) return;
        activePort = prefs.getPort();
        String bindHost = prefs.getBindHost();

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(bindHost);
        connector.setPort(activePort);
        connector.setIdleTimeout(CONNECTOR_IDLE_TIMEOUT_MS);
        server.addConnector(connector);

        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(new AuthGateServlet(lifecycle.buildTransport(), authFilter)),
                       "/mcp/*");
        server.setHandler(ctx);

        server.start();
    }

    public synchronized void stop() throws Exception {
        if (server == null) return;
        lifecycle.close();
        server.stop();
        server.join();
        server = null;
    }

    public boolean isStarted() { return server != null && server.isStarted(); }
    public int getPort() { return activePort; }
}
