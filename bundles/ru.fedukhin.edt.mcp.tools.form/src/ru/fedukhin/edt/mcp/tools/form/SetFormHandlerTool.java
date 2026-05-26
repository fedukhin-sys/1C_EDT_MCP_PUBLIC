package ru.fedukhin.edt.mcp.tools.form;

import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.form.internal.FormFileEditor;

/**
 * {@code set_form_handler} — добавить event handler на форму или item (Stage 8c).
 *
 * <p>Args: {@code { project, formFqn, event, handlerName, itemPath? }}
 * <p>Result: {@code { formFqn, event, handlerName, itemPath, formPath }}
 *
 * <p>itemPath: {@code null} = form-level handler ({@code ПриСозданииНаСервере},
 * {@code ПриОткрытии}), иначе путь "/" к item (например {@code "Таблица"} или
 * {@code "Страницы/Реквизиты/Поле1"}).
 *
 * <p>event — имя события в EDT-API, e.g. {@code OnCreateAtServer}, {@code OnOpen},
 * {@code OnChange}, {@code Click}, {@code BeforeWrite}. Список зависит от kind хоста.
 *
 * <p>handlerName — имя BSL-процедуры в модуле формы. BSL-обработчик должен быть
 * написан отдельно через {@code write_module}.
 */
public final class SetFormHandlerTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final FormFileEditor           editor;

    @Inject
    public SetFormHandlerTool(FormFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public SetFormHandlerTool(Supplier<IWorkspaceRoot> rootSupplier, FormFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "set_form_handler"; }
    @Override public String description() {
        return "Bind an event handler on a form or item. itemPath=null targets form root "
             + "(ПриСозданииНаСервере, ПриОткрытии), else item by '/'-separated path. "
             + "Creates the form Module.bsl if missing; write the handler procedure body "
             + "separately via write_module.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",     str);
        props.put("formFqn",     str);
        props.put("event",       str);
        props.put("handlerName", str);
        props.put("itemPath",    str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "formFqn", "event", "handlerName"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String formFqn     = requireString(args, "formFqn");
        String event       = requireString(args, "event");
        String handlerName = requireString(args, "handlerName");
        String itemPath    = args.get("itemPath") instanceof String s ? s : null;

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        String formPath = AddFormAttributeTool.formFileRelPath(formFqn);
        IFile formFile = project.getFile(formPath);
        if (!formFile.exists()) {
            throw new ToolException("Form.form not found at '" + formPath + "'");
        }

        editor.setHandler(formFile, new FormFileEditor.HandlerSpec(
                itemPath, event, handlerName));

        // BUG-06 fix: ensure the form has a Module.bsl for the bound handler to
        // live in. The handler procedure body itself must be written via write_module.
        String modulePath = ensureModuleFile(project, formPath);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formFqn",     formFqn);
        result.put("event",       event);
        result.put("handlerName", handlerName);
        result.put("itemPath",    itemPath);
        result.put("formPath",    formPath);
        result.put("modulePath",  modulePath);
        return result;
    }

    /** Creates an empty {@code Module.bsl} next to {@code Form.form} if missing; returns its path. */
    private static String ensureModuleFile(IProject project, String formPath) throws ToolException {
        String modulePath = formPath.substring(0, formPath.length() - "Form.form".length())
                + "Module.bsl";
        IFile module = project.getFile(modulePath);
        if (!module.exists()) {
            try {
                module.create(new ByteArrayInputStream(new byte[0]), true, new NullProgressMonitor());
            } catch (CoreException e) {
                throw new ToolException("failed to create form module '" + modulePath
                        + "': " + e.getMessage());
            }
        }
        return modulePath;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
