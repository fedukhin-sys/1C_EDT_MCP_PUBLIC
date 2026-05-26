package ru.fedukhin.edt.mcp.tools.bsl;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader.MethodInfo;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslParseException;

public class ListModuleMethodsTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final BslAstReader reader;

    @Inject
    public ListModuleMethodsTool(BslAstReader reader) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), reader);
    }

    public ListModuleMethodsTool(Supplier<IWorkspaceRoot> rootSupplier, BslAstReader reader) {
        this.rootSupplier = rootSupplier;
        this.reader = reader;
    }

    @Override public String name() { return "list_module_methods"; }
    @Override public String description() { return "List procedures and functions in a BSL module with positions"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> projectProp = new LinkedHashMap<>(); projectProp.put("type", "string");
        Map<String, Object> pathProp = new LinkedHashMap<>(); pathProp.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", projectProp);
        properties.put("path", pathProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "path"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public List<Map<String, Object>> call(Map<String, Object> args) throws ToolException {
        String projectName = ReadModuleTool.stringArg(args, "project");
        String path = ReadModuleTool.stringArg(args, "path");
        if (!path.endsWith(".bsl")) {
            throw new ToolException("not a BSL module: " + path);
        }
        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists()) {
            throw new ToolException("project '" + projectName + "' not found");
        }
        IFile file = project.getFile(path);
        if (!file.exists()) {
            throw new ToolException("module '" + path + "' not found in project '" + projectName + "'");
        }

        List<MethodInfo> infos;
        try {
            infos = reader.listMethods(file);
        } catch (BslParseException e) {
            throw new ToolException("failed to parse BSL: " + e.getMessage());
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (MethodInfo info : infos) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", info.name());
            entry.put("kind", info.kind());
            entry.put("export", info.export());
            entry.put("lineStart", info.lineStart());
            entry.put("lineEnd", info.lineEnd());
            out.add(entry);
        }
        return out;
    }
}
