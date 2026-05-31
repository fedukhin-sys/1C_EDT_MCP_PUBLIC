package ru.fedukhin.edt.mcp.tools.edt.workspace;

import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
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

public class CloseProjectTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final DtProjectLifecycle lifecycle;

    @Inject
    public CloseProjectTool(IV8ProjectManager projectManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(),
             DtProjectLifecycle.production(projectManager));
    }

    public CloseProjectTool(Supplier<IWorkspaceRoot> rootSupplier, DtProjectLifecycle lifecycle) {
        this.rootSupplier = rootSupplier;
        this.lifecycle = lifecycle;
    }

    @Override public String name() { return "close_project"; }
    @Override public String description() { return "Close a project in the workspace (idempotent)"; }

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
        if (project.isOpen()) {
            try {
                project.close(new NullProgressMonitor());
            } catch (CoreException e) {
                throw new ToolException("failed to close '" + name + "': " + e.getMessage());
            }
            // Drain EDT's async DT-project teardown so a later open_project does
            // not race a still-pending teardown job.
            try {
                lifecycle.drainAfterClose(project);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolException("interrupted while closing '" + name + "'");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("open", project.isOpen());
        return out;
    }
}
