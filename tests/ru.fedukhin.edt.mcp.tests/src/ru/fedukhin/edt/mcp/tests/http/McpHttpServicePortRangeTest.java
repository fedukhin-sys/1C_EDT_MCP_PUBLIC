package ru.fedukhin.edt.mcp.tests.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Collections;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
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
 * Вторая инстанция EDT на той же машине обязана сесть на следующий свободный порт,
 * а не упасть BindException и остаться без MCP-сервера. Раньше порт был один
 * (дефолт 3001) и вторая инстанция молча оставалась без сервера.
 */
public class McpHttpServicePortRangeTest {

    private McpHttpService svc;
    private ServerSocket squatter;

    @After
    public void down() throws Exception {
        if (svc != null) svc.stop();
        if (squatter != null) squatter.close();
    }

    private static McpHttpService service(McpPreferences prefs) {
        IToolRegistry registry = new ToolRegistry(Collections.emptyList());
        McpServerLifecycle lifecycle =
                new McpServerLifecycle(registry, new ToolSpecAdapter(null), () -> null);
        return new McpHttpService(lifecycle, new BearerAuthFilter(() -> "t"), prefs);
    }

    private static McpPreferences prefs(String nodeName, int from, int to) {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(nodeName);
        node.putInt(McpPreferences.KEY_PORT, from);
        node.putInt(McpPreferences.KEY_PORT_RANGE_END, to);
        return new McpPreferences(node);
    }

    @Test
    public void start_takesNextFreePortWhenFirstIsBusy() throws Exception {
        int base;
        try (ServerSocket probe = new ServerSocket(0)) {
            base = probe.getLocalPort();
        }
        squatter = new ServerSocket(base, 0, InetAddress.getByName("127.0.0.1"));

        svc = service(prefs("ru.fedukhin.edt.mcp.tests.http.range1", base, base + 3));
        svc.start();

        assertTrue("сервер должен подняться, а не упасть BindException", svc.isStarted());
        assertEquals("должен занять следующий свободный порт", base + 1, svc.getPort());
    }

    @Test
    public void start_failsClearlyWhenWholeRangeBusy() throws Exception {
        int base;
        try (ServerSocket probe = new ServerSocket(0)) {
            base = probe.getLocalPort();
        }
        squatter = new ServerSocket(base, 0, InetAddress.getByName("127.0.0.1"));

        svc = service(prefs("ru.fedukhin.edt.mcp.tests.http.range2", base, base));
        try {
            svc.start();
            fail("ожидалась ошибка: весь диапазон занят");
        } catch (Exception e) {
            assertTrue("сообщение должно называть диапазон, было: " + e.getMessage(),
                    e.getMessage() != null && e.getMessage().contains(String.valueOf(base)));
        }
    }

    @Test
    public void start_usesRequestedPortWhenFree() throws Exception {
        int base;
        try (ServerSocket probe = new ServerSocket(0)) {
            base = probe.getLocalPort();
        }

        svc = service(prefs("ru.fedukhin.edt.mcp.tests.http.range3", base, base + 3));
        svc.start();

        assertEquals("свободный первый порт диапазона занимается как есть", base, svc.getPort());
    }
}
