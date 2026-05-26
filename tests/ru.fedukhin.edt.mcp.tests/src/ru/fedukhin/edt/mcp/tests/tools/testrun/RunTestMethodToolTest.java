package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.testrun.RunTestMethodTool;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher;

public class RunTestMethodToolTest {

    @Test public void name_isRunTestMethod() {
        RunTestMethodTool tool = new RunTestMethodTool(
            mock(TestRunnerLauncher.class), mock(InfobaseRegistry.class), mock(RuntimeCli.class));
        assertEquals("run_test_method", tool.name());
    }

    @Test public void schema_requiresAllOfProjectInfobaseModuleMethod() {
        RunTestMethodTool tool = new RunTestMethodTool(
            mock(TestRunnerLauncher.class), mock(InfobaseRegistry.class), mock(RuntimeCli.class));
        Map<String, Object> schema = tool.inputSchema();
        java.util.List<?> required = (java.util.List<?>) schema.get("required");
        java.util.Set<?> requiredSet = new java.util.HashSet<>(required);
        assertEquals(new java.util.HashSet<>(java.util.List.of(
            "project", "infobase", "moduleFqn", "methodName")), requiredSet);
    }

    @Test public void call_missingMethodName_throws() {
        RunTestMethodTool tool = new RunTestMethodTool(
            mock(TestRunnerLauncher.class), mock(InfobaseRegistry.class), mock(RuntimeCli.class));
        Map<String, Object> args = new HashMap<>();
        args.put("project", "Demo"); args.put("infobase", "DemoIB"); args.put("moduleFqn", "CommonModule.X");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }
}
