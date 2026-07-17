package ru.fedukhin.edt.mcp.tools.form;

import com._1c.g5.v8.bm.core.IBmObject;
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
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.form.internal.FormRegistry;
import ru.fedukhin.edt.mcp.tools.form.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectKind;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;

/**
 * {@code list_forms} — список форм в проекте, опционально по родительскому FQN.
 *
 * <p>Args: {@code { project, parentFqn? }}
 * <p>Result: {@code { forms: [{name, fqn, parentFqn}] }}
 *
 * <p>Spike §9.4: все шесть форм-поддерживающих kinds используют {@code getForms()}.
 * Spike §9.2: FQN формы = {@code <ParentKind>.<ParentName>.Form.<FormName>}.
 */
public final class ListFormsTool implements IMcpTool {

    /** Источник имён контейнеров Configuration ("getCatalogs", "getChartsOfAccounts", …). */
    private static final MdObjectRegistry MD_REGISTRY = new MdObjectRegistry();

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IBmModelManager bm;
    private final IConfigurationProvider cfgProvider;
    private final MdObjectLocator locator;
    private final FormRegistry registry;

    @Inject
    public ListFormsTool(IBmModelManager bm, IConfigurationProvider cfgProvider,
                         MdObjectLocator locator, FormRegistry registry) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), bm, cfgProvider, locator, registry);
    }

    /** Test seam. */
    public ListFormsTool(Supplier<IWorkspaceRoot> rootSupplier,
                         IBmModelManager bm,
                         IConfigurationProvider cfgProvider,
                         MdObjectLocator locator,
                         FormRegistry registry) {
        this.rootSupplier = rootSupplier;
        this.bm           = bm;
        this.cfgProvider  = cfgProvider;
        this.locator      = locator;
        this.registry     = registry;
    }

    @Override public String name()        { return "list_forms"; }
    @Override public String description() {
        return "List forms in a project (optionally filtered by parent MdObject fqn)";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project",   Map.of("type", "string"));
        properties.put("parentFqn", Map.of("type", "string"));
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
        String parentFqn   = (args.get("parentFqn") instanceof String s) ? s : null;

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        List<Map<String, Object>> forms = new ArrayList<>();
        Throwable[] err = new Throwable[1];

        bm.executeReadOnlyTask(project, (IBmSingleNamespaceTask<Void>) txn -> {
            try {
                if (parentFqn != null) {
                    // Filter by specific parent
                    String parentKind = parentFqn.contains(".")
                            ? parentFqn.substring(0, parentFqn.indexOf('.'))
                            : parentFqn;
                    String accessor = registry.accessorFor(parentKind);
                    if (accessor == null) {
                        throw new ToolException(
                                "md object kind '" + parentKind + "' does not support forms");
                    }
                    IBmObject parent = locator.findTop(txn, parentFqn, projectName);
                    addForms((EObject) parent, accessor, parentFqn, forms);
                } else {
                    // All forms: common + all supported kinds
                    Configuration cfg = resolveConfiguration(txn, project);
                    if (cfg != null) {
                        addCommonForms(cfg, forms);
                        // Обходим все kind'ы с формами, а не жёстко зашитую шестёрку: имя
                        // контейнера берём из общего реестра, состав — из FormRegistry.
                        for (String kind : registry.supportedKinds()) {
                            MdObjectKind meta = MD_REGISTRY.get(kind);
                            if (meta == null) continue;
                            iterateKindContainer(cfg, meta.containerFeatureName(), kind, forms);
                        }
                    }
                }
            } catch (ToolException e) {
                err[0] = e;
            }
            return null;
        });

        if (err[0] instanceof ToolException te) throw te;
        return Map.of("forms", forms);
    }

    private Configuration resolveConfiguration(com._1c.g5.v8.bm.core.IBmTransaction txn, IProject project) {
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
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void addForms(EObject parent, String accessor, String parentFqn,
                                 List<Map<String, Object>> out) {
        try {
            Object list = parent.getClass().getMethod(accessor).invoke(parent);
            if (list instanceof Iterable) {
                for (Object f : (Iterable<Object>) list) {
                    if (f instanceof EObject) {
                        Object name = ((EObject) f).getClass().getMethod("getName").invoke(f);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", name);
                        m.put("fqn", parentFqn + ".Form." + name);
                        m.put("parentFqn", parentFqn);
                        out.add(m);
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            // skip — accessor not found
        }
    }

    private static void addCommonForms(EObject cfg, List<Map<String, Object>> out) {
        try {
            @SuppressWarnings("unchecked")
            Iterable<EObject> commons =
                    (Iterable<EObject>) cfg.getClass().getMethod("getCommonForms").invoke(cfg);
            if (commons != null) {
                for (EObject cf : commons) {
                    Object name = cf.getClass().getMethod("getName").invoke(cf);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("fqn", "CommonForm." + name);
                    m.put("parentFqn", "CommonForm");
                    out.add(m);
                }
            }
        } catch (ReflectiveOperationException e) {
            // skip
        }
    }

    private static void iterateKindContainer(EObject cfg, String containerAccessor,
                                             String kindName,
                                             List<Map<String, Object>> out) {
        try {
            @SuppressWarnings("unchecked")
            Iterable<EObject> items =
                    (Iterable<EObject>) cfg.getClass().getMethod(containerAccessor).invoke(cfg);
            if (items != null) {
                for (EObject item : items) {
                    Object name = item.getClass().getMethod("getName").invoke(item);
                    String parentFqn = kindName + "." + name;
                    addForms(item, "getForms", parentFqn, out);
                }
            }
        } catch (ReflectiveOperationException e) {
            // skip
        }
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
