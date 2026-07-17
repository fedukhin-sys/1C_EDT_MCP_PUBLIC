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
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher;

public class RunTestMethodToolTest {

    private static RunTestMethodTool newTool() {
        return new RunTestMethodTool(
            mock(TestRunnerLauncher.class), mock(InfobaseRegistry.class), mock(RuntimeCli.class),
            mock(TestRunnerInstaller.ModuleScaffolder.class));
    }

    @Test public void name_isRunTestMethod() {
        assertEquals("run_test_method", newTool().name());
    }

    @Test public void schema_requiresAllOfProjectInfobaseModuleMethod() {
        Map<String, Object> schema = newTool().inputSchema();
        java.util.List<?> required = (java.util.List<?>) schema.get("required");
        java.util.Set<?> requiredSet = new java.util.HashSet<>(required);
        assertEquals(new java.util.HashSet<>(java.util.List.of(
            "project", "infobase", "moduleFqn", "methodName")), requiredSet);
    }

    @Test public void call_missingMethodName_throws() {
        Map<String, Object> args = new HashMap<>();
        args.put("project", "Demo"); args.put("infobase", "DemoIB"); args.put("moduleFqn", "CommonModule.X");
        try {
            newTool().call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }
}
