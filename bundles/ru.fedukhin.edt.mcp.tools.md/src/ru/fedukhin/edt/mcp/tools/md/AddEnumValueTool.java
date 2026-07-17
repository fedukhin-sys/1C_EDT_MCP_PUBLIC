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
 * {@code add_enum_value} — добавляет значение в перечисление.
 *
 * <p>Args: {@code { project, fqn ("Enum.X"), name, synonym?, comment? }}
 * <p>Result: {@code { fqn, name, mdoPath }}
 *
 * <p>Без этого инструмента {@code create_md_object kind=Enum} создавал перечисление, которое
 * нечем было наполнить. Запись — DOM-маршрутом через {@link MdoFileEditor} (консистентно с
 * {@code add_attribute}/{@code add_tabular_section}: BM-route на .mdo даёт гонки сериализации).
 */
public final class AddEnumValueTool implements IMcpTool {

    private static final String ENUM_KIND = "Enum";

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdoFileEditor            editor;

    @Inject
    public AddEnumValueTool(MdoFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    /** Test seam. */
    public AddEnumValueTool(Supplier<IWorkspaceRoot> rootSupplier, MdoFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_enum_value"; }

    @Override public String description() {
        return "Add a value to an Enum. Existing values are readable via list_attributes/get_md_object.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", str);
        props.put("fqn",     Map.of("type", "string", "description", "Enum FQN, e.g. Enum.OrderStatuses"));
        props.put("name",    str);
        props.put("synonym", Map.of("type", "string", "description", "ru synonym; omitted if empty"));
        props.put("comment", Map.of("type", "string", "description", "comment; omitted if empty"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "fqn", "name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");
        String valueName   = requireString(args, "name");
        String synonym     = optionalString(args, "synonym");
        String comment     = optionalString(args, "comment");

        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot == fqn.length() - 1) {
            throw new ToolException("fqn must be 'Enum.Name': '" + fqn + "'");
        }
        String kind = fqn.substring(0, dot);
        if (!ENUM_KIND.equals(kind)) {
            throw new ToolException("add_enum_value applies to Enum only, got kind '" + kind + "'");
        }
        String enumName = fqn.substring(dot + 1);

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        // BmPersistentExecutor пишет .mdo мимо workspace cache, поэтому сразу после
        // create_md_object файл может числиться несуществующим — как в add_tabular_section.
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String mdoPath = "src/Enums/" + enumName + "/" + enumName + ".mdo";
        IFile mdoFile = project.getFile(mdoPath);
        if (!mdoFile.exists()) {
            throw new ToolException("Enum '.mdo' not found at " + mdoPath);
        }

        editor.addEnumValue(mdoFile, valueName, synonym, comment);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn",     fqn);
        result.put("name",    valueName);
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

    private static String optionalString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}
