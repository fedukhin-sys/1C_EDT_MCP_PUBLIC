package ru.fedukhin.edt.mcp.tools.edt.workspace;

import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.version.Version;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;

public class GetWorkspaceInfoTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IRuntimeRegistry runtimeRegistry;

    @Inject
    public GetWorkspaceInfoTool(IRuntimeRegistry runtimeRegistry) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), runtimeRegistry);
    }

    /** Test seam — pass an explicit root supplier. */
    public GetWorkspaceInfoTool(Supplier<IWorkspaceRoot> rootSupplier, IRuntimeRegistry runtimeRegistry) {
        this.rootSupplier = rootSupplier;
        this.runtimeRegistry = runtimeRegistry;
    }

    @Override public String name() { return "get_workspace_info"; }
    @Override public String description() { return "Get workspace root path, project counts, and registered 1C runtime versions"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        IWorkspaceRoot root = rootSupplier.get();
        IProject[] projects = root.getProjects();
        int openCount = 0;
        for (IProject p : projects) {
            if (p.isOpen()) openCount++;
        }
        // Build version list and compute max version in a single pass.
        // Max version is null when the registry is empty (no installed runtimes).
        List<String> versions = new ArrayList<>();
        Version maxVersion = null;
        for (IRuntime r : runtimeRegistry.getRuntimes()) {
            Version v = r.getVersion();
            versions.add(v.toString());
            if (maxVersion == null || v.compareTo(maxVersion) > 0) {
                maxVersion = v;
            }
        }

        IPath loc = root.getLocation();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", loc == null ? null : loc.toString());
        out.put("projectCount", projects.length);
        out.put("openProjectCount", openCount);
        out.put("runtimeVersions", versions);
        out.put("defaultRuntimeVersion", maxVersion == null ? null : maxVersion.toString());
        return out;
    }
}
