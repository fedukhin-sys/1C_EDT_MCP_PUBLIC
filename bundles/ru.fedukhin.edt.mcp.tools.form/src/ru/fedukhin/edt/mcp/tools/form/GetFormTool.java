package ru.fedukhin.edt.mcp.tools.form;

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
import ru.fedukhin.edt.mcp.tools.form.internal.FormReader;
import ru.fedukhin.edt.mcp.tools.form.internal.MdObjectLocator;

/**
 * {@code get_form} — детальная информация о конкретной форме по FQN.
 *
 * <p>Args: {@code { project, fqn }}
 * <p>Result: {@code { fqn, name, title?, items[], tree[], attributes[], commands[], hasModule, modulePath? }}
 *
 * <p>Spike §9.2: fqn = {@code Catalog.X.Form.Y} или {@code CommonForm.Y}.
 * Form-object в BM — top-object типа CatalogForm/DocumentForm/… (BasicForm subtype).
 * Детальные данные (items, attributes, commands) живут на {@code basicForm.getForm()} —
 * AbstractForm. Но поскольку BasicForm тоже EObject, FormReader применяет рефлексию
 * и автоматически попробует getItems()/getAttributes()/getFormCommands() на нём.
 * Если у BasicForm этих методов нет — FormReader вернёт пустые списки (best-effort).
 */
public final class GetFormTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IBmModelManager bm;
    private final MdObjectLocator locator;
    private final FormReader formReader;

    @Inject
    public GetFormTool(IBmModelManager bm, MdObjectLocator locator, FormReader formReader) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), bm, locator, formReader);
    }

    /** Test seam. */
    public GetFormTool(Supplier<IWorkspaceRoot> rootSupplier,
                       IBmModelManager bm,
                       MdObjectLocator locator,
                       FormReader formReader) {
        this.rootSupplier = rootSupplier;
        this.bm           = bm;
        this.locator      = locator;
        this.formReader   = formReader;
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

        Map<String, Object>[] result = new Map[1];
        Throwable[] err = new Throwable[1];

        bm.executeReadOnlyTask(project, (IBmSingleNamespaceTask<Void>) txn -> {
            try {
                // Stage 6 fix: формы — inline-nested в parent .mdo, не top-objects.
                // findForm делает parent traversal для Kind.Name.Form.X и
                // top-lookup для CommonForm.X.
                IBmObject bmObj = locator.findForm(txn, fqn, projectName);

                // bmObj is BasicForm (CatalogForm, DocumentForm, etc.)
                // Try to get the AbstractForm from getForm() for rich detail
                Object formDetail = getAbstractForm(bmObj);
                Object formForReader = (formDetail != null) ? formDetail : bmObj;

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fqn", fqn);
                m.put("name", deriveName(fqn));

                // Title — from the AbstractForm (Titled interface)
                Object titleMap = invoke(formForReader, "getTitle");
                if (titleMap != null) {
                    String titleStr = FormReader.extractLocalizedString(titleMap);
                    if (titleStr != null && !titleStr.isEmpty()) {
                        m.put("title", titleStr);
                    }
                }

                // Items / tree
                m.put("items", formReader.readItemsFlat(formForReader));
                m.put("tree",  formReader.readItemsTree(formForReader));

                // Attributes / commands
                m.put("attributes", formReader.readAttributes(formForReader));
                m.put("commands",   formReader.readCommands(formForReader));

                // Module
                Object module = invoke(formForReader, "getModule");
                boolean hasModule = (module != null);
                m.put("hasModule", hasModule);
                if (hasModule) {
                    m.put("modulePath", deriveModulePath(fqn));
                }

                result[0] = m;
            } catch (ToolException e) {
                err[0] = e;
            }
            return null;
        });

        if (err[0] instanceof ToolException te) throw te;
        return result[0];
    }

    /** Try to get the AbstractForm from a BasicForm via getForm(). */
    private static Object getAbstractForm(Object bmObj) {
        try {
            Object form = bmObj.getClass().getMethod("getForm").invoke(bmObj);
            return form;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Object invoke(Object obj, String method) {
        if (obj == null) return null;
        try {
            return obj.getClass().getMethod(method).invoke(obj);
        } catch (ReflectiveOperationException e) {
            return null;
        }
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
