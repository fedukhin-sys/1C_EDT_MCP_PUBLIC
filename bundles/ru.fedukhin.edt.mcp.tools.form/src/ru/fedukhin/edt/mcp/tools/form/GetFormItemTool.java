package ru.fedukhin.edt.mcp.tools.form;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
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
import ru.fedukhin.edt.mcp.tools.form.internal.FormReader;
import ru.fedukhin.edt.mcp.tools.form.internal.MdObjectLocator;

/**
 * {@code get_form_item} — детальная информация об одном элементе формы по пути.
 *
 * <p>Args: {@code { project, fqn, itemPath }}
 * <p>Result: {@code { fqn, itemPath, name, type, dataPath?, properties, childCount }}
 *
 * <p>itemPath — путь через "/" от корня формы, e.g. "GroupHeader/FieldName".
 */
public final class GetFormItemTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IBmModelManager bm;
    private final MdObjectLocator locator;
    private final FormReader formReader;

    @Inject
    public GetFormItemTool(IBmModelManager bm, MdObjectLocator locator, FormReader formReader) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), bm, locator, formReader);
    }

    /** Test seam. */
    public GetFormItemTool(Supplier<IWorkspaceRoot> rootSupplier,
                           IBmModelManager bm,
                           MdObjectLocator locator,
                           FormReader formReader) {
        this.rootSupplier = rootSupplier;
        this.bm           = bm;
        this.locator      = locator;
        this.formReader   = formReader;
    }

    @Override public String name()        { return "get_form_item"; }
    @Override public String description() {
        return "Get details of a specific form item by its path within the form";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project",  Map.of("type", "string"));
        properties.put("fqn",      Map.of("type", "string"));
        properties.put("itemPath", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "fqn", "itemPath"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");
        String itemPath    = requireString(args, "itemPath");

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        Map<String, Object>[] result = new Map[1];
        Throwable[] err = new Throwable[1];

        bm.executeReadOnlyTask(project, (IBmSingleNamespaceTask<Void>) txn -> {
            try {
                // Stage 6 fix: формы — inline-nested в parent .mdo, не top-objects.
                IBmObject bmObj = locator.findForm(txn, fqn, projectName);

                // Get the form detail object (AbstractForm if available)
                Object formDetail = getAbstractForm(bmObj);
                Object formForReader = (formDetail != null) ? formDetail : bmObj;

                // Walk itemPath
                String[] segments = itemPath.split("/");
                EObject current = null;
                List<EObject> currentChildren = invokeItems(formForReader);

                for (String segment : segments) {
                    current = findByName(currentChildren, segment, itemPath);
                    currentChildren = invokeItems(current);
                }

                if (current == null) {
                    throw new ToolException("item path '" + itemPath + "' not found in form '" + fqn + "'");
                }

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fqn",      fqn);
                m.put("itemPath", itemPath);
                m.put("name",     invoke(current, "getName"));
                m.put("type",     current.eClass() != null
                                  ? current.eClass().getName()
                                  : current.getClass().getSimpleName());

                Object dataPath = invoke(current, "getDataPath");
                if (dataPath != null) {
                    m.put("dataPath", dataPath.toString());
                }

                // Selected properties (whitelist)
                Map<String, Object> props = new LinkedHashMap<>();
                for (String prop : new String[]{"title", "readOnly", "enabled", "visible"}) {
                    Object v = invoke(current, propertyAccessor(prop));
                    if (v != null) {
                        if (v instanceof Map) {
                            String localized = FormReader.extractLocalizedString(v);
                            if (localized != null) props.put(prop, localized);
                        } else {
                            props.put(prop, v);
                        }
                    }
                }
                m.put("properties", props);
                m.put("childCount", currentChildren.size());

                result[0] = m;
            } catch (ToolException e) {
                err[0] = e;
            }
            return null;
        });

        if (err[0] instanceof ToolException te) throw te;
        return result[0];
    }

    private static EObject findByName(List<EObject> items, String name, String fullPath)
            throws ToolException {
        for (EObject item : items) {
            Object n = invoke(item, "getName");
            if (name.equals(n)) {
                return item;
            }
        }
        throw new ToolException("item '" + name + "' not found (path: '" + fullPath + "')");
    }

    private static String propertyAccessor(String prop) {
        return switch (prop) {
            case "title"    -> "getTitle";
            case "readOnly" -> "isReadOnly";
            case "enabled"  -> "isEnabled";
            case "visible"  -> "isVisible";
            default         -> "get" + prop.substring(0, 1).toUpperCase() + prop.substring(1);
        };
    }

    private static Object getAbstractForm(Object bmObj) {
        try {
            return bmObj.getClass().getMethod("getForm").invoke(bmObj);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Object invoke(Object obj, String method) {
        if (obj == null) return null;
        try {
            return obj.getClass().getMethod(method).invoke(obj);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EObject> invokeItems(Object obj) {
        if (obj == null) return List.of();
        try {
            Object v = obj.getClass().getMethod("getItems").invoke(obj);
            if (v instanceof List) return (List<EObject>) v;
            if (v instanceof Iterable) {
                List<EObject> r = new ArrayList<>();
                for (Object item : (Iterable<?>) v) {
                    if (item instanceof EObject) r.add((EObject) item);
                }
                return r;
            }
        } catch (ReflectiveOperationException e) { /* leaf */ }
        return List.of();
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) s;
    }
}
