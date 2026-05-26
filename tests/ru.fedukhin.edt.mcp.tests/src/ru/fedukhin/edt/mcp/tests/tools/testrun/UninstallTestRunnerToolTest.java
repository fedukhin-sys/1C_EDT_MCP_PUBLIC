package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import java.util.HashMap;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.testrun.UninstallTestRunnerTool;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller;

public class UninstallTestRunnerToolTest {

    @Test public void name_isUninstallTestRunner() {
        UninstallTestRunnerTool tool = new UninstallTestRunnerTool(
            mock(TestRunnerInstaller.class), mock(IV8ProjectManager.class));
        assertEquals("uninstall_test_runner", tool.name());
    }

    @Test public void call_missingProject_throws() {
        UninstallTestRunnerTool tool = new UninstallTestRunnerTool(
            mock(TestRunnerInstaller.class), mock(IV8ProjectManager.class));
        try {
            tool.call(new HashMap<>());
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }
}
