package ru.fedukhin.edt.mcp.tools.form;

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
import ru.fedukhin.edt.mcp.tools.form.internal.FormFileReader;

/**
 * {@code get_form} — детальная информация о конкретной форме по FQN.
 *
 * <p>Args: {@code { project, fqn }}
 * <p>Result: {@code { fqn, name, title?, items[], tree[], attributes[], commands[], hasModule, modulePath? }}
 *
 * <p>fqn = {@code Catalog.X.Form.Y} или {@code CommonForm.Y}.
 *
 * <p>2026-05-31 A8 fix: tool читает {@code Form.form} напрямую с диска (через
 * {@link FormFileReader}), а не из BM-модели. До фикса использовалась BM-рефлексия
 * с мити­гацией {@code refreshLocal + waitModelSynchronization}, которая всё равно
 * могла отдать stale данные сразу после {@code create_form}/{@code add_form_*}
 * (Xtext re-parse отстаёт). Disk-read даёт fresh data всегда и снимает зависимость
 * от BM-sync timing.
 */
public final class GetFormTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final FormFileReader formFileReader;

    @Inject
    public GetFormTool(FormFileReader formFileReader) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), formFileReader);
    }

    /** Test seam. */
    public GetFormTool(Supplier<IWorkspaceRoot> rootSupplier, FormFileReader formFileReader) {
        this.rootSupplier   = rootSupplier;
        this.formFileReader = formFileReader;
    }

    @Override public String name()        { return "get_form"; }
    @Override public String description() {
        return "Get details of a form (items, attributes, commands) by FQN";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", Map.of("type", "string"));
        properties.put("fqn",     Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "fqn"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        // Lightweight refresh — workspace local refresh подхватывает свежезаписанные
        // файлы (например после create_form). BM-sync уже не нужен — мы читаем .form
        // напрямую с диска.
        try {
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        } catch (CoreException ignored) {
            // best-effort
        }

        String formRel = deriveFormPath(fqn);
        if (formRel == null) {
            throw new ToolException("cannot derive Form.form path from fqn '" + fqn
                    + "'; expected 'CommonForm.X' or '<Kind>.<Owner>.Form.<Name>'");
        }
        IFile formFile = project.getFile(formRel);
        if (!formFile.exists()) {
            throw new ToolException("Form.form not found at " + formRel);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fqn", fqn);
        m.put("name", deriveName(fqn));

        String title = formFileReader.readTitle(formFile);
        if (title != null && !title.isEmpty()) m.put("title", title);

        m.put("items",      formFileReader.readItemsFlat(formFile));
        m.put("tree",       formFileReader.readItemsTree(formFile));
        m.put("attributes", formFileReader.readAttributes(formFile));
        m.put("commands",   formFileReader.readCommands(formFile));

        String modulePath = deriveModulePath(fqn);
        IFile moduleFile  = modulePath != null ? project.getFile(modulePath) : null;
        boolean hasModule = moduleFile != null && moduleFile.exists();
        m.put("hasModule", hasModule);
        if (hasModule) m.put("modulePath", modulePath);

        return m;
    }

    /** Derive the disk-path of Form.form from FQN. */
    private static String deriveFormPath(String fqn) {
        if (fqn == null || fqn.isEmpty()) return null;
        if (fqn.startsWith("CommonForm.")) {
            return "src/CommonForms/" + fqn.substring("CommonForm.".length()) + "/Form.form";
        }
        String[] parts = fqn.split("\\.");
        if (parts.length == 4 && "Form".equals(parts[2])) {
            return "src/" + pluralize(parts[0]) + "/" + parts[1] + "/Forms/" + parts[3] + "/Form.form";
        }
        return null;
    }

    /**
     * Derive the form name from its FQN.
     * e.g. "Catalog.Goods.Form.ListForm" → "ListForm"
     *      "CommonForm.SelectionForm" → "SelectionForm"
     */
    private static String deriveName(String fqn) {
        int last = fqn.lastIndexOf('.');
        return last >= 0 ? fqn.substring(last + 1) : fqn;
    }

    /**
     * Heuristic module path from FQN.
     * e.g. "Catalog.Goods.Form.ListForm" → "src/Catalogs/Goods/Forms/ListForm/Module.bsl"
     *      "CommonForm.SelectionForm"    → "src/CommonForms/SelectionForm/Module.bsl"
     */
    private static String deriveModulePath(String fqn) {
        // CommonForm.<Name>
        if (fqn.startsWith("CommonForm.")) {
            String name = fqn.substring("CommonForm.".length());
            return "src/CommonForms/" + name + "/Module.bsl";
        }
        // <Kind>.<ObjName>.Form.<FormName>
        String[] parts = fqn.split("\\.");
        if (parts.length == 4) {
            // e.g. Catalog → Catalogs, Document → Documents, etc.
            String kindPlural = pluralize(parts[0]);
            return "src/" + kindPlural + "/" + parts[1] + "/Forms/" + parts[3] + "/Module.bsl";
        }
        return null;
    }

    private static String pluralize(String kind) {
        return switch (kind) {
            case "Catalog"             -> "Catalogs";
            case "Document"            -> "Documents";
            case "InformationRegister" -> "InformationRegisters";
            case "AccumulationRegister" -> "AccumulationRegisters";
            case "DataProcessor"       -> "DataProcessors";
            case "Report"              -> "Reports";
            default                    -> kind + "s";
        };
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) s;
    }
}
