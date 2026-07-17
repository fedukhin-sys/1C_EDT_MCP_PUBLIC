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

public class InstallTestRunnerTool implements IMcpTool {

    private final TestRunnerInstaller installer;
    private final IV8ProjectManager projectManager;

    @Inject
    public InstallTestRunnerTool(TestRunnerInstaller installer, IV8ProjectManager projectManager) {
        this.installer = installer;
        this.projectManager = projectManager;
    }

    @Override public String name() { return "install_test_runner"; }
    @Override public String description() {
        return "Scaffold xUnit test runner modules + ПриНачалеРаботыСистемы handler. "
             + "The runner is launched by run_tests via 1cv8 ENTERPRISE: deploy_project the "
             + "project first, and make sure the infobase either has no users, accepts OS "
             + "authentication, or is given user/password in run_tests.";
    }

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
        var res = installer.install(v8);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("installed", !res.alreadyInstalled());
        out.put("alreadyInstalled", res.alreadyInstalled());
        out.put("mode", res.mode());
        out.put("modules", List.of("CommonModule." + res.clientModule(),
                                    "CommonModule." + res.serverModule()));
        out.put("handlerLocation", res.handlerLocation());
        if (res.warningInvasive()) {
            out.put("warning", "test runner handler added to main configuration; "
                + "consider developing in an extension for non-invasive scaffolding");
        }
        return out;
    }
}
