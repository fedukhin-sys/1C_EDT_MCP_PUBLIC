package ru.fedukhin.edt.mcp.tools.edt.workspace;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;

public class OpenProjectTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;

    @Inject
    public OpenProjectTool() {
        this(() -> ResourcesPlugin.getWorkspace().getRoot());
    }

    public OpenProjectTool(Supplier<IWorkspaceRoot> rootSupplier) {
        this.rootSupplier = rootSupplier;
    }

    @Override public String name() { return "open_project"; }
    @Override public String description() { return "Open a project in the workspace (idempotent)"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String name = GetProjectTool.stringArg(args, "name");
        IProject project = rootSupplier.get().getProject(name);
        if (!project.exists()) {
            throw new ToolException("project '" + name + "' not found");
        }
        if (!project.isOpen()) {
            try {
                project.open(new NullProgressMonitor());
            } catch (CoreException e) {
                throw new ToolException("failed to open '" + name + "': " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("open", project.isOpen());
        return out;
    }
}
