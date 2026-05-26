package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.testrun.RunTestsTool;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher;

public class RunTestsToolTest {

    @Test public void name_isRunTests() {
        RunTestsTool tool = new RunTestsTool(
            mock(TestRunnerLauncher.class), mock(InfobaseRegistry.class), mock(RuntimeCli.class));
        assertEquals("run_tests", tool.name());
    }

    @Test public void schema_includesTimeoutSecondsWithRange() {
        RunTestsTool tool = new RunTestsTool(
            mock(TestRunnerLauncher.class), mock(InfobaseRegistry.class), mock(RuntimeCli.class));
        Map<String, Object> schema = tool.inputSchema();
        Map<?,?> properties = (Map<?,?>) schema.get("properties");
        assertTrue(properties.containsKey("timeoutSeconds"));
        Map<?,?> timeout = (Map<?,?>) properties.get("timeoutSeconds");
        assertEquals("integer", timeout.get("type"));
        assertEquals(30, timeout.get("minimum"));
        assertEquals(3600, timeout.get("maximum"));
    }

    @Test public void call_missingProject_throws() {
        RunTestsTool tool = new RunTestsTool(
            mock(TestRunnerLauncher.class), mock(InfobaseRegistry.class), mock(RuntimeCli.class));
        try {
            tool.call(new HashMap<>());
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }

    @Test public void call_missingInfobase_throws() {
        RunTestsTool tool = new RunTestsTool(
            mock(TestRunnerLauncher.class), mock(InfobaseRegistry.class), mock(RuntimeCli.class));
        Map<String, Object> args = new HashMap<>();
        args.put("project", "Demo");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }
}
