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
 * {@code add_form_command} — добавляет FormCommand к форме.
 *
 * <p>Args: {@code { project, formFqn, name, title?, handlerName? }}
 * <p>Result: {@code { formFqn, name, handlerName, formPath }}
 *
 * <p>handlerName по умолчанию = name (соглашение 1С). Action создаётся типа
 * {@code form:FormCommandHandlerContainer} с одиночным {@code <handler><name>X</name></handler>}.
 * BSL-обработчик в модуле формы ДОЛЖЕН быть написан отдельно (через
 * {@code write_module} или Stage 8c set_form_handler).
 */
public final class AddFormCommandTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final FormFileEditor           editor;

    @Inject
    public AddFormCommandTool(FormFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    /** Test seam. */
    public AddFormCommandTool(Supplier<IWorkspaceRoot> rootSupplier, FormFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_form_command"; }
    @Override public String description() {
        return "Add a FormCommand to an existing form. handlerName defaults to name. "
             + "BSL handler procedure must be written separately via write_module.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> strType = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",     strType);
        props.put("formFqn",     strType);
        props.put("name",        strType);
        props.put("title",       strType);
        props.put("handlerName", strType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "formFqn", "name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String formFqn     = requireString(args, "formFqn");
        String cmdName     = requireString(args, "name");
        String title       = args.get("title")       instanceof String s ? s : null;
        String handlerArg  = args.get("handlerName") instanceof String s ? s : null;

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        String formPath = AddFormAttributeTool.formFileRelPath(formFqn);
        IFile formFile = project.getFile(formPath);
        if (!formFile.exists()) {
            throw new ToolException("Form.form not found at '" + formPath + "'");
        }

        String handlerName = (handlerArg != null && !handlerArg.isEmpty()) ? handlerArg : cmdName;
        editor.addCommand(formFile,
                new FormFileEditor.CommandSpec(cmdName, title, handlerName));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formFqn",     formFqn);
        result.put("name",        cmdName);
        result.put("handlerName", handlerName);
        result.put("formPath",    formPath);
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
