package ru.fedukhin.edt.mcp.tools.form;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.form.internal.FormFileEditor;

/**
 * {@code add_form_attribute} — добавляет атрибут (FormAttribute) к существующей форме.
 *
 * <p>Args: {@code { project, formFqn, name, type, title?, main? }}
 * <p>Result: {@code { formFqn, name, type, formPath }}
 *
 * <p>Type-выражения:
 * <ul>
 *   <li>{@code String(50)} / {@code String} — строка с/без длины</li>
 *   <li>{@code Number(10,2)} / {@code Number} — число с precision/scale</li>
 *   <li>{@code Date} / {@code Date(DateTime)} — дата</li>
 *   <li>{@code Boolean}</li>
 *   <li>{@code CatalogRef.X}, {@code DocumentRef.X}, {@code EnumRef.X}, {@code CatalogObject.X}, etc.</li>
 * </ul>
 *
 * <p>Stage 8b v1: только добавление, никакой синхронизации с item-tree формы
 * (Stage 8c: add_form_field bound to attribute via dataPath).
 */
public final class AddFormAttributeTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final FormFileEditor           editor;

    @Inject
    public AddFormAttributeTool(FormFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    /** Test seam. */
    public AddFormAttributeTool(Supplier<IWorkspaceRoot> rootSupplier, FormFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_form_attribute"; }
    @Override public String description() {
        return "Add a FormAttribute (data attribute) to an existing form. Type examples: "
             + "String(50), Number(10,2), Date, Boolean, CatalogRef.Goods.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> strType = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", strType);
        props.put("formFqn", strType);
        props.put("name",    strType);
        props.put("type",    strType);
        props.put("title",   strType);
        props.put("main",    Map.of("type", "boolean"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "formFqn", "name", "type"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String formFqn     = requireString(args, "formFqn");
        String attrName    = requireString(args, "name");
        String type        = requireString(args, "type");
        String title       = args.get("title") instanceof String s ? s : null;
        boolean main       = args.get("main") instanceof Boolean b && b;

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        String formPath = formFileRelPath(formFqn);
        IFile formFile = project.getFile(formPath);
        if (!formFile.exists()) {
            throw new ToolException("Form.form not found at '" + formPath
                    + "' — form may not exist or was created in a build prior to Stage 8a v3");
        }

        editor.addAttribute(formFile, new FormFileEditor.AttributeSpec(attrName, type, title, main));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formFqn",  formFqn);
        result.put("name",     attrName);
        result.put("type",     type);
        result.put("formPath", formPath);
        return result;
    }

    /**
     * "Catalog.X.Form.Y" → "src/Catalogs/X/Forms/Y/Form.form".
     */
    static String formFileRelPath(String formFqn) throws ToolException {
        String[] parts = formFqn.split("\\.");
        if (parts.length != 4 || !"Form".equals(parts[2])) {
            throw new ToolException("formFqn must be '<ParentKind>.<ParentName>.Form.<FormName>': '"
                    + formFqn + "'");
        }
        return "src/" + parts[0] + "s/" + parts[1] + "/Forms/" + parts[3] + "/Form.form";
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
