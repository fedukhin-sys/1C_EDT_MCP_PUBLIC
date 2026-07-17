package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.DebugClientTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugEventSource;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugLaunchResult;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugLauncher;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.ExceptionBreakpointService;

public class DebugClientToolTest {

    private final DebugLauncher launcher = mock(DebugLauncher.class);
    private final DebugSessionRegistry registry = new DebugSessionRegistry();
    private final DebugClientTool tool = new DebugClientTool(launcher, registry);

    @Test
    public void metadata_isCorrect() {
        assertEquals("debug_client", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_happyPath_launchesRegistersAndReturnsFacts() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        ILaunch launch = mock(ILaunch.class);
        Process clientProc = mock(Process.class);
        when(clientProc.pid()).thenReturn(4321L);
        when(launcher.launch(eq("DemoIB"), eq("thin"), any(), any()))
                .thenReturn(new DebugLaunchResult(target, launch, clientProc, "DemoIB", "thin"));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("infobase", "DemoIB");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args);

        assertNotNull(out.get("debugSessionId"));
        assertNotNull(out.get("clientSessionId"));
        assertEquals(4321L, out.get("pid"));
        assertEquals("thin", out.get("clientType"));
        assertEquals("DemoIB", out.get("infobase"));
        assertNotNull(out.get("startedAt"));
        // the session is now in the registry, addressable by the returned id
        assertEquals(1, registry.list().size());
    }

    @Test
    public void call_defaultsToThinClient() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        Process clientProc = mock(Process.class);
        when(clientProc.pid()).thenReturn(1L);
        when(launcher.launch(eq("DemoIB"), eq("thin"), any(), any()))
                .thenReturn(new DebugLaunchResult(target, mock(ILaunch.class), clientProc, "DemoIB", "thin"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("infobase", "DemoIB"); // no clientType -> defaults to "thin"

        tool.call(args);

        org.mockito.Mockito.verify(launcher).launch(eq("DemoIB"), eq("thin"), any(), any());
    }

    @Test
    public void call_explicitThickClient_isForwarded() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        Process clientProc = mock(Process.class);
        when(clientProc.pid()).thenReturn(1L);
        when(launcher.launch(eq("DemoIB"), eq("thick"), any(), any()))
                .thenReturn(new DebugLaunchResult(target, mock(ILaunch.class), clientProc, "DemoIB", "thick"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("infobase", "DemoIB");
        args.put("clientType", "thick");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args);

        org.mockito.Mockito.verify(launcher).launch(eq("DemoIB"), eq("thick"), any(), any());
        assertEquals("thick", out.get("clientType"));
    }

    @Test
    public void call_missingInfobase_throwsToolException() {
        try {
            tool.call(new LinkedHashMap<>());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("infobase"));
        }
    }

    @Test
    public void call_launcherThrows_propagatesToolException() throws Exception {
        when(launcher.launch(any(), any(), any(), any()))
                .thenThrow(new ToolException("infobase 'X' not found"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("infobase", "X");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    @Test
    public void call_launcherThrows_removesInstalledExceptionBreakpoint() throws Exception {
        // catch-all breakpoint ставится ДО запуска клиента (чтобы debug-server подхватил его
        // при attach). Если запуск упал, breakpoint остаётся в workspace breakpoint manager:
        // он глобальный, и следующий обычный (не-MCP) сеанс отладки начнёт останавливаться
        // на каждом исключении BSL. Снимать его на неуспешном пути — обязанность инструмента,
        // так как DebugSession (который чистит его в terminate()) в этом случае не создаётся.
        ExceptionBreakpointService bpService = mock(ExceptionBreakpointService.class);
        IBreakpoint bp = mock(IBreakpoint.class);
        when(bpService.installCatchAll()).thenReturn(bp);
        DebugClientTool toolWithBp = new DebugClientTool(
                launcher, registry, mock(DebugEventSource.class), bpService);
        when(launcher.launch(any(), any(), any(), any()))
                .thenThrow(new ToolException("dbgs boom"));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("infobase", "DemoIB");
        try {
            toolWithBp.call(args);
            fail("expected ToolException");
        } catch (ToolException expected) {
            // ожидаемо
        }

        org.mockito.Mockito.verify(bpService).remove(bp);
    }
}
