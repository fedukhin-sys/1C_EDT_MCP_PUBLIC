package ru.fedukhin.edt.mcp.tools.edt.workspace;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;

public class GetProjectTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IV8ProjectManager projectManager;
    private final IRuntimeVersionSupport versionSupport;
    private final IConfigurationProvider configProvider;

    @Inject
    public GetProjectTool(IV8ProjectManager projectManager, IRuntimeVersionSupport versionSupport,
                          IConfigurationProvider configProvider) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), projectManager, versionSupport, configProvider);
    }

    public GetProjectTool(Supplier<IWorkspaceRoot> rootSupplier,
                          IV8ProjectManager projectManager,
                          IRuntimeVersionSupport versionSupport,
                          IConfigurationProvider configProvider) {
        this.rootSupplier = rootSupplier;
        this.projectManager = projectManager;
        this.versionSupport = versionSupport;
        this.configProvider = configProvider;
    }

    @Override public String name() { return "get_project"; }
    @Override public String description() { return "Get details of a project by name (type, location, version, open flag)"; }

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
        String name = stringArg(args, "name");
        IProject project = rootSupplier.get().getProject(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        if (!project.exists()) {
            out.put("exists", false);
            return out;
        }
        out.put("exists", true);
        out.put("open", project.isOpen());
        out.put("location", project.getLocation() == null ? null : project.getLocation().toString());
        IV8Project v8 = projectManager.getProject(project);
        out.put("type", classify(v8));
        Version v = versionSupport.getRuntimeVersion(project);
        out.put("version", v == null ? null : v.toString());
        String scriptVariant = null;
        if (v8 instanceof IConfigurationProject) {
            try {
                Configuration cfg = configProvider.getConfiguration(project);
                if (cfg != null && cfg.getScriptVariant() != null) {
                    scriptVariant = cfg.getScriptVariant().name();
                }
            } catch (RuntimeException ignore) {
                // Configuration may be transiently unavailable; leave scriptVariant null.
            }
        }
        out.put("scriptVariant", scriptVariant);
        return out;
    }

    private static String classify(IV8Project p) {
        if (p instanceof IConfigurationProject) return "configuration";
        if (p instanceof IExtensionProject) return "extension";
        if (p instanceof IExternalObjectProject) return "external-object";
        return "unknown";
    }

    /**
     * Validates that {@code args[key]} is a non-empty string. Used by sibling tools
     * (Tasks 6-10) that also need a required string argument — package-visible intentionally.
     */
    static String stringArg(Map<String, Object> args, String key) throws ToolException {
        Object v = (args == null) ? null : args.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new ToolException("missing or empty '" + key + "' argument");
        }
        return (String) v;
    }
}
