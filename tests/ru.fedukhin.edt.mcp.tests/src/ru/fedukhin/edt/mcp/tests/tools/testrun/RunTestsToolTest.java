package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.testrun.RunTestsTool;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher;

public class RunTestsToolTest {

    private static RunTestsTool newTool() {
        return new RunTestsTool(
            mock(TestRunnerLauncher.class), mock(InfobaseRegistry.class), mock(RuntimeCli.class),
            mock(TestRunnerInstaller.ModuleScaffolder.class));
    }

    @Test public void name_isRunTests() {
        assertEquals("run_tests", newTool().name());
    }

    @Test public void schema_includesTimeoutSecondsWithRange() {
        Map<String, Object> schema = newTool().inputSchema();
        Map<?,?> properties = (Map<?,?>) schema.get("properties");
        assertTrue(properties.containsKey("timeoutSeconds"));
        Map<?,?> timeout = (Map<?,?>) properties.get("timeoutSeconds");
        assertEquals("integer", timeout.get("type"));
        assertEquals(30, timeout.get("minimum"));
        assertEquals(3600, timeout.get("maximum"));
    }

    @Test public void call_missingProject_throws() {
        try {
            newTool().call(new HashMap<>());
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }

    @Test public void call_missingInfobase_throws() {
        Map<String, Object> args = new HashMap<>();
        args.put("project", "Demo");
        try {
            newTool().call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }

    @Test public void requireRunnerInstalled_missing_throwsWithInstallHint() {
        TestRunnerInstaller.ModuleScaffolder scaffolder =
            mock(TestRunnerInstaller.ModuleScaffolder.class);
        when(scaffolder.exists(any(), anyString())).thenReturn(false);
        try {
            RunTestsTool.requireRunnerInstalled(scaffolder, mock(IProject.class), "Demo");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue("message should point to install_test_runner: " + e.getMessage(),
                e.getMessage().contains("install_test_runner"));
            assertTrue("message should name the project: " + e.getMessage(),
                e.getMessage().contains("Demo"));
        }
    }

    @Test public void requireRunnerInstalled_present_passes() throws ToolException {
        TestRunnerInstaller.ModuleScaffolder scaffolder =
            mock(TestRunnerInstaller.ModuleScaffolder.class);
        when(scaffolder.exists(any(), anyString())).thenReturn(true);
        RunTestsTool.requireRunnerInstalled(scaffolder, mock(IProject.class), "Demo");
    }

    @Test public void requireRunnerInstalled_partialInstall_throws() {
        TestRunnerInstaller.ModuleScaffolder scaffolder =
            mock(TestRunnerInstaller.ModuleScaffolder.class);
        when(scaffolder.exists(any(), anyString())).thenReturn(false);
        when(scaffolder.exists(any(),
            eq("CommonModule." + TestRunnerInstaller.CLIENT_MODULE))).thenReturn(true);
        try {
            RunTestsTool.requireRunnerInstalled(scaffolder, mock(IProject.class), "Demo");
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }
}
