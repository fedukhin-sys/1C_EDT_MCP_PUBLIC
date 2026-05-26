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

/**
 * {@code rename_attribute} — переименовывает attribute/dimension/resource MdObject.
 *
 * <p>Args: {@code { project, fqn, role?, oldName, newName }}.
 * <p>Result: {@code { fqn, role, oldName, newName }}.
 */
public final class RenameAttributeTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final BmPersistentExecutor    executor;
    private final MdObjectLocator         locator;

    @Inject
    public RenameAttributeTool(BmPersistentExecutor executor, MdObjectLocator locator) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), executor, locator);
    }

    /** Test seam. */
    public RenameAttributeTool(Supplier<IWorkspaceRoot> rootSupplier,
                               BmPersistentExecutor executor, MdObjectLocator locator) {
        this.rootSupplier   = rootSupplier;
        this.executor       = executor;
        this.locator        = locator;
    }

    @Override public String name()        { return "rename_attribute"; }
    @Override public String description() { return "Rename an attribute/dimension/resource of an MdObject"; }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> strType = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", strType);
        props.put("fqn",     strType);
        props.put("role",    strType);
        props.put("oldName", strType);
        props.put("newName", strType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "fqn", "oldName", "newName"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");
        String role        = args.get("role") instanceof String s ? s : "Attribute";
        String oldName     = requireString(args, "oldName");
        String newName     = requireString(args, "newName");

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        Throwable[] err = new Throwable[1];

        executor.execute(project, "MCP rename_attribute " + fqn + "/" + oldName + " → " + newName,
            (IBmSingleNamespaceTask<Void>) txn -> {
                try {
                    IBmObject bmObj = locator.findTop(txn, fqn, projectName);
                    EObject parent = (EObject) bmObj;

                    String accessor;
                    switch (role) {
                        case "Attribute": accessor = "getAttributes"; break;
                        case "Dimension": accessor = "getDimensions"; break;
                        case "Resource":  accessor = "getResources";  break;
                        default:
                            throw new ToolException("unknown role '" + role
                                    + "'; expected Attribute, Dimension, or Resource");
                    }

                    Object listObj;
                    try {
                        listObj = parent.getClass().getMethod(accessor).invoke(parent);
                    } catch (ReflectiveOperationException e) {
                        throw new ToolException("cannot access collection '" + accessor + "' on " + fqn);
                    }
                    if (!(listObj instanceof Iterable)) {
                        throw new ToolException("collection is not iterable on " + fqn);
                    }
                    @SuppressWarnings("unchecked")
                    EObject child = locator.findInList((Iterable<EObject>) listObj, oldName);
                    try {
                        child.getClass().getMethod("setName", String.class).invoke(child, newName);
                    } catch (ReflectiveOperationException e) {
                        throw new ToolException("setName failed: " + e.getMessage());
                    }
                } catch (ToolException te) {
                    err[0] = te;
                }
                return null;
            });

        if (err[0] instanceof ToolException te) throw te;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn",     fqn);
        result.put("role",    role);
        result.put("oldName", oldName);
        result.put("newName", newName);
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
