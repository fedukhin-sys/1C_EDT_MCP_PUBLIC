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

public class OpenProjectTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final DtProjectLifecycle lifecycle;

    @Inject
    public OpenProjectTool(IV8ProjectManager projectManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(),
             DtProjectLifecycle.production(projectManager));
    }

    public OpenProjectTool(Supplier<IWorkspaceRoot> rootSupplier, DtProjectLifecycle lifecycle) {
        this.rootSupplier = rootSupplier;
        this.lifecycle = lifecycle;
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
        // Wait for EDT to register and keep the DT project; surface a warning if
        // it does not activate (BUG-NEW-B — pending interactive data migration).
        String warning;
        try {
            warning = lifecycle.awaitActivation(project);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("interrupted while waiting for '" + name + "' to activate");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("open", project.isOpen());
        if (warning != null) {
            out.put("warning", warning);
        }
        return out;
    }
}
