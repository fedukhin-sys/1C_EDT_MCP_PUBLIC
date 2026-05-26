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
 * {@code rename_md_object} — переименовывает top-level MdObject (BM-only, alpha).
 *
 * <p>Args: {@code { project, fqn, newName }}.
 * <p>Result: {@code { oldFqn, newFqn, kind, newName }}.
 *
 * <p>Spike 5 amendment: только setName, без detach+attach.
 */
public final class RenameMdObjectTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final BmPersistentExecutor    executor;
    private final MdObjectLocator         locator;

    @Inject
    public RenameMdObjectTool(BmPersistentExecutor executor, MdObjectLocator locator) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), executor, locator);
    }

    /** Test seam. */
    public RenameMdObjectTool(Supplier<IWorkspaceRoot> rootSupplier,
                              BmPersistentExecutor executor, MdObjectLocator locator) {
        this.rootSupplier   = rootSupplier;
        this.executor       = executor;
        this.locator        = locator;
    }

    @Override public String name()        { return "rename_md_object"; }
    @Override public String description() { return "Rename a top-level MdObject (BM-only, α)"; }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> strType = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", strType);
        props.put("fqn",     strType);
        props.put("newName", strType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "fqn", "newName"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");
        String newName     = requireString(args, "newName");

        int dot = fqn.indexOf('.');
        if (dot <= 0) {
            throw new ToolException("fqn '" + fqn + "' must be in form Kind.Name");
        }
        String kind   = fqn.substring(0, dot);
        String newFqn = kind + "." + newName;

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        Throwable[] err = new Throwable[1];

        executor.execute(project, "MCP rename_md_object " + fqn + " → " + newName,
            (IBmSingleNamespaceTask<Void>) txn -> {
                try {
                    IBmObject bmObj = locator.findTop(txn, fqn, projectName);
                    EObject obj = (EObject) bmObj;
                    obj.getClass().getMethod("setName", String.class).invoke(obj, newName);
                } catch (ToolException te) {
                    err[0] = te;
                } catch (ReflectiveOperationException e) {
                    err[0] = new ToolException("setName failed on object: " + e.getMessage());
                }
                return null;
            });

        if (err[0] instanceof ToolException te) throw te;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oldFqn",  fqn);
        result.put("newFqn",  newFqn);
        result.put("kind",    kind);
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
