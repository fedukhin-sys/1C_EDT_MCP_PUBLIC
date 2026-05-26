package ru.fedukhin.edt.mcp.tools.bsl;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslParseException;

public class GetModuleInfoTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final BslAstReader reader;

    @Inject
    public GetModuleInfoTool(BslAstReader reader) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), reader);
    }

    public GetModuleInfoTool(Supplier<IWorkspaceRoot> rootSupplier, BslAstReader reader) {
        this.rootSupplier = rootSupplier;
        this.reader = reader;
    }

    @Override public String name() { return "get_module_info"; }
    @Override public String description() { return "Get module type, charset, and method count of a BSL module"; }

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

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
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

        String charsetName;
        try {
            charsetName = file.getCharset();
        } catch (CoreException e) {
            throw new ToolException("failed to detect charset: " + e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projectName);
        out.put("path", path);
        out.put("charset", charsetName);
        out.put("moduleType", reader.getModuleType(file));
        try {
            out.put("methodCount", reader.listMethods(file).size());
            out.put("parseError", null);
        } catch (BslParseException e) {
            out.put("methodCount", null);
            out.put("parseError", e.getMessage());
        }
        return out;
    }
}
