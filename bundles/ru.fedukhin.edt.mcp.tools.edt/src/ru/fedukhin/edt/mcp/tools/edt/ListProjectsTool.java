package ru.fedukhin.edt.mcp.tools.edt;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;

public class ListProjectsTool implements IMcpTool {

    private final IV8ProjectManager projectManager;
    private final IRuntimeVersionSupport versionSupport;

    @Inject
    public ListProjectsTool(IV8ProjectManager projectManager, IRuntimeVersionSupport versionSupport) {
        this.projectManager = projectManager;
        this.versionSupport = versionSupport;
    }

    @Override public String name() { return "list_projects"; }
    @Override public String description() { return "List opened 1C projects in the workspace"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public List<Map<String, Object>> call(Map<String, Object> args) throws ToolException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IV8Project p : projectManager.getProjects()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", p.getProject().getName());
            entry.put("type", classify(p));
            entry.put("version", String.valueOf(versionSupport.getRuntimeVersion(p.getProject())));
            out.add(entry);
        }
        return out;
    }

    private static String classify(IV8Project p) {
        if (p instanceof IConfigurationProject) return "configuration";
        if (p instanceof IExtensionProject) return "extension";
        if (p instanceof IExternalObjectProject) return "external-object";
        return "unknown";
    }
}
