package ru.fedukhin.edt.mcp.tools.testrun;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.CreateInfobaseTool;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller;

public class UninstallTestRunnerTool implements IMcpTool {

    private final TestRunnerInstaller installer;
    private final IV8ProjectManager projectManager;

    @Inject
    public UninstallTestRunnerTool(TestRunnerInstaller installer, IV8ProjectManager projectManager) {
        this.installer = installer;
        this.projectManager = projectManager;
    }

    @Override public String name() { return "uninstall_test_runner"; }
    @Override public String description() { return "Remove xUnit test runner scaffolding"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> proj = new LinkedHashMap<>(); proj.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>(); properties.put("project", proj);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = CreateInfobaseTool.stringArg(args, "project");
        var iproject = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!iproject.exists() || !iproject.isOpen()) {
            throw new ToolException("project '" + projectName + "' not open");
        }
        IV8Project v8 = projectManager.getProject(iproject);
        if (v8 == null) {
            throw new ToolException("project '" + projectName + "' is not a 1C project");
        }
        boolean removed = installer.uninstall(v8);
        Map<String, Object> out = new LinkedHashMap<>();
        if (removed) out.put("removed", true);
        else out.put("wasNotInstalled", true);
        return out;
    }
}
