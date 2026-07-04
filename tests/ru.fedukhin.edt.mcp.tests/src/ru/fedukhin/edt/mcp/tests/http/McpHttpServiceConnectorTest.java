package ru.fedukhin.edt.mcp.tests.http;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.Collections;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.IToolRegistry;
import ru.fedukhin.edt.mcp.core.internal.http.BearerAuthFilter;
import ru.fedukhin.edt.mcp.core.internal.http.McpHttpService;
import ru.fedukhin.edt.mcp.core.internal.preferences.McpPreferences;
import ru.fedukhin.edt.mcp.core.internal.protocol.McpServerLifecycle;
import ru.fedukhin.edt.mcp.core.internal.protocol.ToolSpecAdapter;
import ru.fedukhin.edt.mcp.core.internal.registry.ToolRegistry;

/**
 * The MCP SSE connection is long-lived and silent between requests. Jetty's
 * default {@code ServerConnector} idle timeout is 30s — short enough that
 * ordinary pauses in interactive MCP use let Jetty close the socket, after
 * which the client's next fetch hits a dead connection ("fetch failed").
 * {@code McpHttpService} must configure the connector with a generous idle
 * timeout instead of relying on the 30s default.
 */
public class McpHttpServiceConnectorTest {

    private McpHttpService svc;

    @After
    public void down() throws Exception {
        if (svc != null) svc.stop();
    }

    @Test
    public void start_usesGenerousConnectorIdleTimeout() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        IEclipsePreferences node =
                InstanceScope.INSTANCE.getNode("ru.fedukhin.edt.mcp.tests.http.connector");
        node.putInt(McpPreferences.KEY_PORT, port);
        McpPreferences prefs = new McpPreferences(node);

        IToolRegistry registry = new ToolRegistry(Collections.emptyList());
        McpServerLifecycle lifecycle = new McpServerLifecycle(registry, new ToolSpecAdapter(null));
        svc = new McpHttpService(lifecycle, new BearerAuthFilter(() -> "t"), prefs);
        svc.start();

        long idleTimeout = connectorIdleTimeout(svc);
        assertTrue("connector idle timeout must be well above Jetty's 30s default so the SSE "
                + "socket survives idle gaps in interactive use, was " + idleTimeout + "ms",
                idleTimeout >= 120_000L);
    }

    /** White-box: read the live Jetty connector's idle timeout after start(). */
    private static long connectorIdleTimeout(McpHttpService service) throws Exception {
        Field f = McpHttpService.class.getDeclaredField("server");
        f.setAccessible(true);
        Server server = (Server) f.get(service);
        Connector[] connectors = server.getConnectors();
        assertTrue("server should have at least one connector", connectors.length > 0);
        return connectors[0].getIdleTimeout();
    }
}
