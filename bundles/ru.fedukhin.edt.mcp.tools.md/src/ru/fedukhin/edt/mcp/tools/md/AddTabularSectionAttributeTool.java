package ru.fedukhin.edt.mcp.tools.md;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * {@code add_tabular_section_attribute} — добавляет column в существующую
 * TabularSection.
 *
 * <p>Args: {@code { project, tsFqn, name, type }}
 * <p>{@code tsFqn} формата {@code Kind.Owner.TabularSection.Name}.
 * <p>type-выражения те же что в {@code add_attribute}: String(N), Number(P,S),
 * Date, Boolean, CatalogRef.X, DocumentRef.X, EnumRef.X, etc.
 */
public final class AddTabularSectionAttributeTool implements IMcpTool {

    private static final Map<String, String> KIND_FOLDER = Map.of(
            "Catalog",                    "Catalogs",
            "Document",                   "Documents",
            "BusinessProcess",            "BusinessProcesses",
            "Task",                       "Tasks",
            "ChartOfAccounts",            "ChartsOfAccounts",
            "ChartOfCalculationTypes",    "ChartsOfCalculationTypes",
            "ChartOfCharacteristicTypes", "ChartsOfCharacteristicTypes"
    );

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdoFileEditor            editor;

    @Inject
    public AddTabularSectionAttributeTool(MdoFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public AddTabularSectionAttributeTool(Supplier<IWorkspaceRoot> rootSupplier, MdoFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_tabular_section_attribute"; }
    @Override public String description() {
        return "Add a column (attribute) to an existing TabularSection. "
             + "tsFqn = 'Kind.Owner.TabularSection.Name'. Type examples: String(50), CatalogRef.X.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", str);
        props.put("tsFqn",   str);
        props.put("name",    str);
        props.put("type",    str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "tsFqn", "name", "type"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String tsFqn       = requireString(args, "tsFqn");
        String colName     = requireString(args, "name");
        String type        = requireString(args, "type");

        // tsFqn = "Kind.Owner.TabularSection.Name"
        String[] parts = tsFqn.split("\\.");
        if (parts.length != 4 || !"TabularSection".equals(parts[2])) {
            throw new ToolException("tsFqn must be 'Kind.Owner.TabularSection.Name': '" + tsFqn + "'");
        }
        String kind = parts[0];
        String ownerName = parts[1];
        String tsName = parts[3];

        String folder = KIND_FOLDER.get(kind);
        if (folder == null) {
            throw new ToolException("kind '" + kind + "' does not support tabular sections");
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String mdoPath = "src/" + folder + "/" + ownerName + "/" + ownerName + ".mdo";
        IFile mdoFile = project.getFile(mdoPath);
        if (!mdoFile.exists()) {
            throw new ToolException("parent MdObject '.mdo' not found at " + mdoPath);
        }

        editor.addTabularSectionAttribute(mdoFile,
                new MdoFileEditor.TsAttributeSpec(tsName, colName, type));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tsFqn",   tsFqn);
        result.put("name",    colName);
        result.put("type",    type);
        result.put("mdoPath", mdoPath);
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
