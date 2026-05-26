package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.AttributeReader;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringFormatter;

/**
 * {@code get_md_object} — возвращает детали одного MdObject по FQN.
 *
 * <p>Результат: {@code { kind, name, fqn, synonym, comment, properties, attributes[] }}.
 */
public final class GetMdObjectTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IBmModelManager         bmModelManager;
    private final IConfigurationProvider  cfgProvider;
    private final IV8ProjectManager       projectManager;
    private final MdObjectLocator         locator;
    private final MdObjectRegistry        registry;
    private final TypeStringFormatter     formatter;

    @Inject
    public GetMdObjectTool(IBmModelManager bm, IConfigurationProvider cfgProvider,
                           IV8ProjectManager projectManager,
                           MdObjectLocator locator, MdObjectRegistry registry,
                           TypeStringFormatter formatter) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), bm, cfgProvider,
             projectManager, locator, registry, formatter);
    }

    /** Test seam. */
    public GetMdObjectTool(Supplier<IWorkspaceRoot> rootSupplier,
                           IBmModelManager bm, IConfigurationProvider cfgProvider,
                           IV8ProjectManager projectManager,
                           MdObjectLocator locator, MdObjectRegistry registry,
                           TypeStringFormatter formatter) {
        this.rootSupplier   = rootSupplier;
        this.bmModelManager = bm;
        this.cfgProvider    = cfgProvider;
        this.projectManager = projectManager;
        this.locator        = locator;
        this.registry       = registry;
        this.formatter      = formatter;
    }

    @Override public String name()        { return "get_md_object"; }
    @Override public String description() { return "Get details of a single MdObject by FQN"; }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> strType = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", strType);
        props.put("fqn",     strType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "fqn"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");

        // Parse kind from fqn
        int dot = fqn.indexOf('.');
        if (dot <= 0) {
            throw new ToolException("fqn '" + fqn + "' must be in form Kind.Name");
        }
        String kind = fqn.substring(0, dot);
        if (registry.get(kind) == null && !"Configuration".equals(kind)) {
            throw new ToolException("unknown kind '" + kind + "' in fqn '" + fqn + "'");
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        Map<String, Object>[] result = new Map[1];
        Throwable[] err = new Throwable[1];

        bmModelManager.executeReadOnlyTask(project,
            (IBmSingleNamespaceTask<Void>) txn -> {
                try {
                    IBmObject bmObj = locator.findTop(txn, fqn, projectName);
                    EObject obj = (EObject) bmObj;

                    String name    = invokeGetName(obj);
                    String synonym = extractSynonym(obj, project);
                    String comment = extractComment(obj);
                    Map<String, Object> properties = extractProperties(obj, kind);
                    List<Map<String, Object>> attributes = AttributeReader.readAll(obj, kind, formatter);

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("kind",       kind);
                    r.put("name",       name);
                    r.put("fqn",        fqn);
                    r.put("synonym",    synonym);
                    r.put("comment",    comment);
                    r.put("properties", properties);
                    r.put("attributes", attributes);
                    result[0] = r;
                } catch (ToolException te) {
                    err[0] = te;
                }
                return null;
            });

        if (err[0] instanceof ToolException te) throw te;
        return result[0];
    }

    private String extractSynonym(EObject obj, IProject project) {
        try {
            @SuppressWarnings("unchecked")
            EMap<String, String> map = (EMap<String, String>) obj.getClass().getMethod("getSynonym").invoke(obj);
            if (map == null) return "";
            // Попытка получить язык по умолчанию
            String langCode = "ru";
            if (projectManager != null) {
                try {
                    var v8 = projectManager.getProject(project);
                    if (v8 != null) {
                        var lang = v8.getDefaultLanguage();
                        if (lang != null && lang.getLanguageCode() != null) {
                            langCode = lang.getLanguageCode();
                        }
                    }
                } catch (Throwable t) {
                    // fallback
                }
            }
            String val = map.get(langCode);
            if (val == null) val = "";
            return val;
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    private static String extractComment(EObject obj) {
        try {
            Object c = obj.getClass().getMethod("getComment").invoke(obj);
            return c instanceof String s ? s : "";
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    private static Map<String, Object> extractProperties(EObject obj, String kind) {
        if (!"CommonModule".equals(kind)) {
            return Map.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("server",              invokeFlag(obj, "isServer"));
        p.put("client",              invokeFlag(obj, "isClientManagedApplication"));
        p.put("externalConnection",  invokeFlag(obj, "isExternalConnection"));
        p.put("global",              invokeFlag(obj, "isGlobal"));
        p.put("privileged",          invokeFlag(obj, "isPrivileged"));
        p.put("serverCallsAllowed",  invokeFlag(obj, "isServerCall"));
        return p;
    }

    private static boolean invokeFlag(EObject obj, String method) {
        try {
            Object r = obj.getClass().getMethod(method).invoke(obj);
            return Boolean.TRUE.equals(r);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static String invokeGetName(EObject obj) throws ToolException {
        try {
            Object n = obj.getClass().getMethod("getName").invoke(obj);
            return n instanceof String s ? s : "";
        } catch (ReflectiveOperationException e) {
            throw new ToolException("object does not have getName(): " + obj.getClass().getSimpleName());
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
