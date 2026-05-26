package ru.fedukhin.edt.mcp.tests.protocol;

import static org.junit.Assert.assertNotNull;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.IToolRegistry;
import ru.fedukhin.edt.mcp.core.internal.protocol.McpServerLifecycle;
import ru.fedukhin.edt.mcp.core.internal.protocol.ToolSpecAdapter;
import ru.fedukhin.edt.mcp.core.internal.registry.ToolRegistry;

/**
 * The MCP SDK schedules SSE keep-alive pings only when the transport is built
 * with a non-null {@code keepAliveInterval}. Without them the SSE socket is
 * silent between requests and Jetty closes it on idle, so the client's next
 * fetch fails. {@code buildTransport()} must enable keep-alive.
 */
public class McpServerLifecycleKeepAliveTest {

    @Test
    public void buildTransport_enablesSseKeepAlive() throws Exception {
        IToolRegistry registry = new ToolRegistry(Collections.emptyList());
        McpServerLifecycle lifecycle = new McpServerLifecycle(registry, new ToolSpecAdapter());

        HttpServletSseServerTransportProvider transport = lifecycle.buildTransport();

        // The SDK builds a KeepAliveScheduler only when a non-null interval is passed;
        // a non-null scheduler is the only observable proof keep-alive was enabled.
        Field f = HttpServletSseServerTransportProvider.class.getDeclaredField("keepAliveScheduler");
        f.setAccessible(true);
        assertNotNull("buildTransport() must enable SSE keep-alive (keepAliveInterval) so the "
                + "transport pings idle SSE connections and Jetty does not close them",
                f.get(transport));
    }
}
