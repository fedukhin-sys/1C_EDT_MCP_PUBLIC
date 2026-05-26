package ru.fedukhin.edt.mcp.tools.infobase;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
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
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

public class AssociateInfobaseTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final InfobaseRegistry registry;
    private final IInfobaseAssociationManager assoc;

    @Inject
    public AssociateInfobaseTool(InfobaseRegistry registry, IInfobaseAssociationManager assoc) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), registry, assoc);
    }

    public AssociateInfobaseTool(Supplier<IWorkspaceRoot> rootSupplier,
                                 InfobaseRegistry registry,
                                 IInfobaseAssociationManager assoc) {
        this.rootSupplier = rootSupplier;
        this.registry = registry;
        this.assoc = assoc;
    }

    @Override public String name() { return "associate_infobase"; }
    @Override public String description() { return "Associate a project with an infobase (optionally as default)"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> proj = new LinkedHashMap<>(); proj.put("type", "string");
        Map<String, Object> ib = new LinkedHashMap<>(); ib.put("type", "string");
        Map<String, Object> def = new LinkedHashMap<>(); def.put("type", "boolean");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", proj);
        properties.put("infobase", ib);
        properties.put("setDefault", def);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "infobase"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = CreateInfobaseTool.stringArg(args, "project");
        String infobaseName = CreateInfobaseTool.stringArg(args, "infobase");
        Object sd = args == null ? null : args.get("setDefault");
        boolean setDefault = sd instanceof Boolean ? (Boolean) sd : true;

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not open");
        }
        InfobaseReference ref = registry.findByName(infobaseName).orElseThrow(() ->
            new ToolException("infobase '" + infobaseName + "' not found"));

        try {
            assoc.associate(project, ref, InfobaseAssociationSettings.notSynchronized());
            if (setDefault) {
                assoc.setDefaultInfobase(project, ref, InfobaseAssociationContext.empty());
            }
        } catch (InfobaseAssociationException e) {
            throw new ToolException("association failed: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projectName);
        out.put("infobase", infobaseName);
        out.put("default", setDefault);
        return out;
    }
}
