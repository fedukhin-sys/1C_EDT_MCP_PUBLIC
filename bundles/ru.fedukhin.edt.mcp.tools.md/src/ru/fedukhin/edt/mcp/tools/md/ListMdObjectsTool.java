package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectKind;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;

/**
 * {@code list_md_objects} — list top-level MdObjects in a project,
 * optionally filtered by kind.
 *
 * <p>Result: {@code { "objects": [ { "kind", "name", "fqn" } ] }}
 *
 * <p>Resolves Configuration root via Spike 8 two-path strategy
 * (literal FQN "Configuration" → IConfigurationProvider fallback).
 * Uses {@code executeReadOnlyTask} for a read-only BM transaction.
 *
 * <p>Проекты внешних объектов Configuration root не имеют вовсе — они перечисляются файловым
 * сканом {@code src/ExternalDataProcessors|ExternalReports}.
 */
public final class ListMdObjectsTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IBmModelManager         bmModelManager;
    private final IConfigurationProvider  cfgProvider;
    private final MdObjectRegistry        registry;

    @Inject
    public ListMdObjectsTool(IBmModelManager bm, IConfigurationProvider cfgProvider,
                             MdObjectRegistry registry) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), bm, cfgProvider, registry);
    }

    /** Test seam. */
    public ListMdObjectsTool(Supplier<IWorkspaceRoot> rootSupplier,
                             IBmModelManager bm,
                             IConfigurationProvider cfgProvider,
                             MdObjectRegistry registry) {
        this.rootSupplier   = rootSupplier;
        this.bmModelManager = bm;
        this.cfgProvider    = cfgProvider;
        this.registry       = registry;
    }

    @Override public String name()        { return "list_md_objects"; }
    @Override public String description() { return "List MdObjects in a project, optionally filtered by kind"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> strType = new LinkedHashMap<>();
        strType.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", new LinkedHashMap<>(strType));
        properties.put("kind",    new LinkedHashMap<>(strType));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String filterKind  = (args.get("kind") instanceof String s) ? s : null;

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        // Проекты внешних объектов не имеют Configuration root — BM-маршрут на них падал
        // с «cannot resolve Configuration root». Перечисляем их файловым сканом.
        if (isExternalObjectProject(project)) {
            return Map.of("objects", scanExternalObjects(project, filterKind));
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        Throwable[] err = new Throwable[1];

        bmModelManager.executeReadOnlyTask(project,
            (IBmSingleNamespaceTask<Void>) txn -> {
                try {
                    Configuration cfg = resolveConfiguration(txn, project);
                    for (MdObjectKind k : registry.allKinds()) {
                        if (filterKind != null && !filterKind.equals(k.name())) continue;
                        Object listObj;
                        try {
                            listObj = cfg.getClass().getMethod(k.containerFeatureName()).invoke(cfg);
                        } catch (ReflectiveOperationException e) {
                            continue;
                        }
                        if (!(listObj instanceof Iterable)) continue;
                        for (Object o : (Iterable<?>) listObj) {
                            if (!(o instanceof EObject)) continue;
                            Object objName;
                            try {
                                objName = ((EObject) o).getClass().getMethod("getName").invoke(o);
                            } catch (ReflectiveOperationException e) {
                                continue;
                            }
                            Map<String, Object> e = new LinkedHashMap<>();
                            e.put("kind", k.name());
                            e.put("name", objName);
                            e.put("fqn",  k.name() + "." + objName);
                            entries.add(e);
                        }
                    }
                } catch (ToolException te) {
                    err[0] = te;
                }
                return null;
            });

        if (err[0] instanceof ToolException te) throw te;
        return Map.of("objects", entries);
    }

    /**
     * Spike 8: Path 1 (literal "Configuration") + Path 2 (IConfigurationProvider fallback).
     */
    /** kind внешнего объекта → папка в {@code src/}. */
    private static final Map<String, String> EXTERNAL_KIND_FOLDER = Map.of(
            "ExternalDataProcessor", "ExternalDataProcessors",
            "ExternalReport",        "ExternalReports");

    /**
     * Проект внешних объектов узнаётся по папкам {@code src/ExternalDataProcessors|ExternalReports}:
     * в конфигурации и расширении их не бывает, а Configuration root — наоборот, есть только там.
     */
    private static boolean isExternalObjectProject(IProject project) {
        return EXTERNAL_KIND_FOLDER.values().stream()
                .map(folder -> project.getFolder("src/" + folder))
                .anyMatch(folder -> folder != null && folder.exists());
    }

    /**
     * Перечисляет внешние объекты по файлам: {@code src/<Folder>/<Name>/<Name>.mdo}.
     */
    private static List<Map<String, Object>> scanExternalObjects(IProject project, String filterKind)
            throws ToolException {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, String> kindFolder : EXTERNAL_KIND_FOLDER.entrySet()) {
            String kind = kindFolder.getKey();
            if (filterKind != null && !filterKind.equals(kind)) continue;
            IFolder folder = project.getFolder("src/" + kindFolder.getValue());
            if (folder == null || !folder.exists()) continue;
            IResource[] members;
            try {
                members = folder.members();
            } catch (CoreException e) {
                throw new ToolException("cannot read " + folder.getFullPath() + ": " + e.getMessage(), e);
            }
            for (IResource member : members) {
                if (!(member instanceof IFolder objectFolder)) continue;
                String name = objectFolder.getName();
                if (!objectFolder.getFile(name + ".mdo").exists()) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("kind", kind);
                e.put("name", name);
                e.put("fqn",  kind + "." + name);
                entries.add(e);
            }
        }
        return entries;
    }

    private Configuration resolveConfiguration(IBmTransaction txn, IProject project)
            throws ToolException {
        IBmObject byLiteral = txn.getTopObjectByFqn("Configuration");
        if (byLiteral instanceof Configuration) {
            return (Configuration) byLiteral;
        }
        Configuration global = cfgProvider.getConfiguration(project);
        if (global != null) {
            EObject viaProvider = txn.toTransactionObject(global);
            if (viaProvider instanceof Configuration) {
                return (Configuration) viaProvider;
            }
        }
        throw new ToolException("cannot resolve Configuration root in project '"
                + project.getName() + "'");
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
