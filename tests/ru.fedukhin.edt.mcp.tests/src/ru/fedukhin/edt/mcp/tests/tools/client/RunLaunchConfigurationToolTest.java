package ru.fedukhin.edt.mcp.tests.tools.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.RunLaunchConfigurationTool;
import ru.fedukhin.edt.mcp.tools.client.internal.LaunchConfigService;

public class RunLaunchConfigurationToolTest {

    /** Package-private test seam (probeMs=0) — достаём рефлексией из соседнего пакета. */
    private static RunLaunchConfigurationTool tool(LaunchConfigService service) throws Exception {
        Constructor<RunLaunchConfigurationTool> ctor = RunLaunchConfigurationTool.class
            .getDeclaredConstructor(LaunchConfigService.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(service, 0L);
    }

    private static LaunchConfigService serviceReturning(String name, Optional<ILaunch> result)
            throws Exception {
        ILaunchConfiguration cfg = mock(ILaunchConfiguration.class);
        when(cfg.getName()).thenReturn(name);
        LaunchConfigService service = mock(LaunchConfigService.class);
        when(service.findByName(name)).thenReturn(cfg);
        when(service.launchWithTimeout(eq(cfg), eq("run"), eq(300))).thenReturn(result);
        when(service.launchWithTimeout(eq(cfg), eq("debug"), eq(300))).thenReturn(result);
        return service;
    }

    private static IProcess aliveProcess(String label) {
        IProcess p = mock(IProcess.class);
        when(p.getLabel()).thenReturn(label);
        when(p.isTerminated()).thenReturn(false);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(RunLaunchConfigurationTool tool, Map<String, Object> args)
            throws Exception {
        return (Map<String, Object>) tool.call(args);
    }

    @Test
    public void call_happy_reportsProcessesAlive() throws Exception {
        IProcess alive = aliveProcess("1cv8c.exe");   // мок ДО when(): вложенное стаббинг ломает Mockito
        ILaunch launch = mock(ILaunch.class);
        when(launch.getProcesses()).thenReturn(new IProcess[] { alive });
        when(launch.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        LaunchConfigService service = serviceReturning("Upiter Тонкий клиент", Optional.of(launch));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Upiter Тонкий клиент");
        Map<String, Object> out = call(tool(service), args);

        assertEquals(Boolean.TRUE, out.get("completed"));
        assertEquals("run", out.get("mode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> processes = (List<Map<String, Object>>) out.get("processes");
        assertEquals(1, processes.size());
        assertEquals("1cv8c.exe", processes.get(0).get("label"));
        assertEquals(Boolean.FALSE, processes.get(0).get("terminated"));
        assertEquals(0, ((Number) out.get("debugTargets")).intValue());
        assertNull("живой запуск не требует warning", out.get("warning"));
    }

    /** 1cv8 сообщает отказ только exit-кодом: мгновенно умерший процесс — это warning, не success. */
    @Test
    public void call_processDiedImmediately_warnsWithExitCode() throws Exception {
        IProcess dead = mock(IProcess.class);
        when(dead.getLabel()).thenReturn("1cv8c.exe");
        when(dead.isTerminated()).thenReturn(true);
        when(dead.getExitValue()).thenReturn(1);
        ILaunch launch = mock(ILaunch.class);
        when(launch.getProcesses()).thenReturn(new IProcess[] { dead });
        when(launch.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        LaunchConfigService service = serviceReturning("Upiter Тонкий клиент", Optional.of(launch));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Upiter Тонкий клиент");
        Map<String, Object> out = call(tool(service), args);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> processes = (List<Map<String, Object>>) out.get("processes");
        assertEquals(1, ((Number) processes.get(0).get("exitCode")).intValue());
        assertNotNull("мёртвый клиент должен сопровождаться warning", out.get("warning"));
    }

    @Test
    public void call_debugMode_reportsDebugTargets() throws Exception {
        IProcess alive = aliveProcess("1cv8c.exe");
        IDebugTarget target = mock(IDebugTarget.class);
        ILaunch launch = mock(ILaunch.class);
        when(launch.getProcesses()).thenReturn(new IProcess[] { alive });
        when(launch.getDebugTargets()).thenReturn(new IDebugTarget[] { target });
        LaunchConfigService service = serviceReturning("Upiter Тонкий клиент", Optional.of(launch));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Upiter Тонкий клиент");
        args.put("mode", "debug");
        Map<String, Object> out = call(tool(service), args);

        assertEquals("debug", out.get("mode"));
        assertEquals(1, ((Number) out.get("debugTargets")).intValue());
    }

    /** Таймаут запуска — не ошибка: completed=false и пояснение, запуск продолжается в фоне. */
    @Test
    public void call_timeout_reportsNotCompleted() throws Exception {
        LaunchConfigService service = serviceReturning("Долгая", Optional.empty());

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Долгая");
        Map<String, Object> out = call(tool(service), args);

        assertEquals(Boolean.FALSE, out.get("completed"));
        assertNotNull(out.get("note"));
        assertNull(out.get("processes"));
    }

    @Test
    public void call_invalidMode_throws() throws Exception {
        LaunchConfigService service = mock(LaunchConfigService.class);
        Map<String, Object> args = new HashMap<>();
        args.put("name", "X");
        args.put("mode", "profile");
        try {
            tool(service).call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("profile"));
        }
    }

    @Test
    public void call_timeoutOutOfRange_throws() throws Exception {
        LaunchConfigService service = mock(LaunchConfigService.class);
        Map<String, Object> args = new HashMap<>();
        args.put("name", "X");
        args.put("timeoutSeconds", 5);
        try {
            tool(service).call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("timeoutSeconds"));
        }
    }

    @Test
    public void call_missingName_throws() throws Exception {
        try {
            tool(mock(LaunchConfigService.class)).call(new HashMap<>());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("name"));
        }
    }
}
