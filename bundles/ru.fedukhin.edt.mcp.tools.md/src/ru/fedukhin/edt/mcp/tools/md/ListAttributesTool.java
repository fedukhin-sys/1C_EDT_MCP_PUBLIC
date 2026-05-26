package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
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
import ru.fedukhin.edt.mcp.tools.md.internal.AttributeReader;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringFormatter;

/**
 * {@code list_attributes} — перечисляет attributes/dimensions/resources MdObject.
 *
 * <p>Args: {@code { project, fqn }}.
 * <p>Result: {@code { attributes: AttributeInfo[] }}.
 */
public final class ListAttributesTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IBmModelManager         bmModelManager;
    private final MdObjectLocator         locator;
    private final TypeStringFormatter     formatter;

    @Inject
    public ListAttributesTool(IBmModelManager bm, MdObjectLocator locator,
                              TypeStringFormatter formatter) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), bm, locator, formatter);
    }

    /** Test seam. */
    public ListAttributesTool(Supplier<IWorkspaceRoot> rootSupplier,
                              IBmModelManager bm, MdObjectLocator locator,
                              TypeStringFormatter formatter) {
        this.rootSupplier   = rootSupplier;
        this.bmModelManager = bm;
        this.locator        = locator;
        this.formatter      = formatter;
    }

    @Override public String name()        { return "list_attributes"; }
    @Override public String description() { return "List attributes/dimensions/resources of an MdObject"; }

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

        int dot = fqn.indexOf('.');
        if (dot <= 0) {
            throw new ToolException("fqn '" + fqn + "' must be in form Kind.Name");
        }
        String kind = fqn.substring(0, dot);

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        List<Map<String, Object>>[] result = new List[1];
        Throwable[] err = new Throwable[1];

        bmModelManager.executeReadOnlyTask(project,
            (IBmSingleNamespaceTask<Void>) txn -> {
                try {
                    IBmObject bmObj = locator.findTop(txn, fqn, projectName);
                    EObject obj = (EObject) bmObj;
                    result[0] = AttributeReader.readAll(obj, kind, formatter);
                } catch (ToolException te) {
                    err[0] = te;
                }
                return null;
            });

        if (err[0] instanceof ToolException te) throw te;
        return Map.of("attributes", result[0] != null ? result[0] : List.of());
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
