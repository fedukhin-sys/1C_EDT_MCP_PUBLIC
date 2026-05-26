package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import jakarta.inject.Inject;
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
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;

/**
 * {@code set_md_property} — устанавливает свойство MdObject или его атрибута.
 *
 * <p>Args: {@code { project, fqn, path?, property, value }}.
 * <p>Result: {@code { fqn, path?, property, value }}.
 *
 * <p>path — опциональный путь вида "Attributes/Code" для доступа к дочернему элементу.
 */
public final class SetMdPropertyTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final BmPersistentExecutor    executor;
    private final MdObjectLocator         locator;
    private final PropertyAccessor        accessor;

    @Inject
    public SetMdPropertyTool(BmPersistentExecutor executor, MdObjectLocator locator,
                             PropertyAccessor accessor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), executor, locator, accessor);
    }

    /** Test seam. */
    public SetMdPropertyTool(Supplier<IWorkspaceRoot> rootSupplier,
                             BmPersistentExecutor executor, MdObjectLocator locator,
                             PropertyAccessor accessor) {
        this.rootSupplier   = rootSupplier;
        this.executor       = executor;
        this.locator        = locator;
        this.accessor       = accessor;
    }

    @Override public String name()        { return "set_md_property"; }
    @Override public String description() { return "Set a property on an MdObject or its attribute"; }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> strType = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",  strType);
        props.put("fqn",      strType);
        props.put("path",     strType);
        props.put("property", strType);
        props.put("value",    Map.of());
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "fqn", "property", "value"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");
        String path        = args.get("path") instanceof String s ? s : null;
        String property    = requireString(args, "property");
        Object value       = args.get("value");

        int dot = fqn.indexOf('.');
        if (dot <= 0) {
            throw new ToolException("fqn '" + fqn + "' must be in form Kind.Name");
        }
        String kind = fqn.substring(0, dot);

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        Throwable[] err = new Throwable[1];

        executor.execute(project, "MCP set_md_property " + fqn + "/" + property,
            (IBmSingleNamespaceTask<Void>) txn -> {
                try {
                    IBmObject bmObj = locator.findTop(txn, fqn, projectName);
                    EObject parent = (EObject) bmObj;
                    EObject target;
                    String targetKind;

                    if (path == null) {
                        target     = parent;
                        targetKind = kind;
                    } else {
                        // path = "Attributes/Code" or "Dimensions/Period" or "Resources/Amount"
                        int slash = path.indexOf('/');
                        if (slash <= 0) {
                            throw new ToolException("path '" + path + "' must be in form Coll/Name");
                        }
                        String coll = path.substring(0, slash);
                        String elemName = path.substring(slash + 1);

                        String accessor2;
                        switch (coll) {
                            case "Attributes":  accessor2 = "getAttributes"; targetKind = "Attribute"; break;
                            case "Dimensions":  accessor2 = "getDimensions"; targetKind = "Dimension"; break;
                            case "Resources":   accessor2 = "getResources";  targetKind = "Resource";  break;
                            default:
                                throw new ToolException("unknown collection '" + coll
                                        + "'; expected Attributes, Dimensions, or Resources");
                        }

                        Object listObj;
                        try {
                            listObj = parent.getClass().getMethod(accessor2).invoke(parent);
                        } catch (ReflectiveOperationException e) {
                            throw new ToolException("cannot access collection '" + coll + "' on kind " + kind);
                        }
                        if (!(listObj instanceof Iterable)) {
                            throw new ToolException("collection '" + coll + "' is not iterable on kind " + kind);
                        }
                        @SuppressWarnings("unchecked")
                        EObject found = locator.findInList((Iterable<EObject>) listObj, elemName);
                        target = found;
                    }

                    accessor.set(target, targetKind, project, property, value);
                } catch (ToolException te) {
                    err[0] = te;
                }
                return null;
            });

        if (err[0] instanceof ToolException te) throw te;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn",      fqn);
        if (path != null) result.put("path", path);
        result.put("property", property);
        result.put("value",    value);
        return result;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
