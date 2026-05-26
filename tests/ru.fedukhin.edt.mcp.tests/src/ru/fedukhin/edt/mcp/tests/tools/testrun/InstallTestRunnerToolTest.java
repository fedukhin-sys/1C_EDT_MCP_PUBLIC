package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.testrun.InstallTestRunnerTool;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller;

public class InstallTestRunnerToolTest {

    @Test public void name_isInstallTestRunner() {
        InstallTestRunnerTool tool = new InstallTestRunnerTool(
            mock(TestRunnerInstaller.class), mock(IV8ProjectManager.class));
        assertEquals("install_test_runner", tool.name());
    }

    @Test public void schema_hasProjectAsRequired() {
        InstallTestRunnerTool tool = new InstallTestRunnerTool(
            mock(TestRunnerInstaller.class), mock(IV8ProjectManager.class));
        Map<String, Object> schema = tool.inputSchema();
        assertEquals("object", schema.get("type"));
        assertTrue(((Map<?,?>) schema.get("properties")).containsKey("project"));
        assertTrue(((java.util.List<?>) schema.get("required")).contains("project"));
    }

    @Test public void call_missingProject_throws() {
        InstallTestRunnerTool tool = new InstallTestRunnerTool(
            mock(TestRunnerInstaller.class), mock(IV8ProjectManager.class));
        try {
            tool.call(new HashMap<>());
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }
}
