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
 * {@code add_form_table} — Table bound to data path (TabularSection or
 * DynamicList) (Stage 8c).
 *
 * <p>Args: {@code { project, formFqn, name, dataPath, parentPath?, title? }}
 * <p>Result: {@code { formFqn, name, dataPath, parentPath, formPath }}
 *
 * <p>dataPath — имя form-attribute типа TabularSection или DynamicList,
 * e.g. {@code Список} или {@code Товары}. Колонки таблицы (FormField children)
 * добавляются отдельным вызовом {@code add_form_field} с parentPath={tableName}.
 */
public final class AddFormTableTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final FormFileEditor           editor;

    @Inject
    public AddFormTableTool(FormFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public AddFormTableTool(Supplier<IWorkspaceRoot> rootSupplier, FormFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_form_table"; }
    @Override public String description() {
        return "Add a Table bound to a TabularSection or DynamicList. Columns are added "
             + "via add_form_field with parentPath=<table name>.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",    str);
        props.put("formFqn",    str);
        props.put("name",       str);
        props.put("dataPath",   str);
        props.put("parentPath", str);
        props.put("title",      str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "formFqn", "name", "dataPath"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String formFqn     = requireString(args, "formFqn");
        String tableName   = requireString(args, "name");
        String dataPath    = requireString(args, "dataPath");
        String parentPath  = args.get("parentPath") instanceof String s ? s : null;
        String title       = args.get("title")      instanceof String s ? s : null;

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        String formPath = AddFormAttributeTool.formFileRelPath(formFqn);
        IFile formFile = project.getFile(formPath);
        if (!formFile.exists()) {
            throw new ToolException("Form.form not found at '" + formPath + "'");
        }

        editor.addTable(formFile, new FormFileEditor.TableSpec(
                tableName, dataPath, parentPath, title));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formFqn",    formFqn);
        result.put("name",       tableName);
        result.put("dataPath",   dataPath);
        result.put("parentPath", parentPath);
        result.put("formPath",   formPath);
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
