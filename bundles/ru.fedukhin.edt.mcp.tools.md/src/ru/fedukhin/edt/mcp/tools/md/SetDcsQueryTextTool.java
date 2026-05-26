package ru.fedukhin.edt.mcp.tools.md;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.DcsFileEditor;

/**
 * {@code set_dcs_query_text} — заменить текст {@code <query>} в существующем
 * {@code <dataSet xsi:type="DataSetQuery">} в .dcs.
 *
 * <p>Args: {@code { project, reportFqn, templateName?, dataSetName, query }}.
 * <p>Result: {@code { reportFqn, templateName, dcsPath, dataSetName, updated }}.
 *
 * <p>Replace-or-throw: dataSet должен существовать и быть DataSetQuery. Идемпотентно
 * если новый query == старому.
 */
public final class SetDcsQueryTextTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final DcsFileEditor           editor;

    @Inject
    public SetDcsQueryTextTool(DcsFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public SetDcsQueryTextTool(Supplier<IWorkspaceRoot> rootSupplier, DcsFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "set_dcs_query_text"; }
    @Override public String description() {
        return "Stage 8f: replace the <query> text on an existing <dataSet xsi:type=\"DataSetQuery\"> "
             + "in Template.dcs. Args: reportFqn, templateName?, dataSetName, query. Throws if dataSet "
             + "not found or not DataSetQuery. Idempotent when new text equals old.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",      str);
        props.put("reportFqn",    str);
        props.put("templateName", str);
        props.put("dataSetName",  str);
        props.put("query",        str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "reportFqn", "dataSetName", "query"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName  = requireString(args, "project");
        String reportFqn    = requireString(args, "reportFqn");
        String dataSetName  = requireString(args, "dataSetName");
        String query        = (args.get("query") instanceof String s) ? s : "";
        String templateName = args.get("templateName") instanceof String t && !t.isEmpty()
                ? t : "ОсновнаяСхема";

        IFile dcsFile = AddDcsCalculatedFieldTool.resolveDcs(
                rootSupplier.get(), projectName, reportFqn, templateName);
        boolean updated = editor.setDataSetQuery(dcsFile, dataSetName, query);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportFqn",    reportFqn);
        result.put("templateName", templateName);
        result.put("dcsPath",      dcsFile.getProjectRelativePath().toString());
        result.put("dataSetName",  dataSetName);
        result.put("updated",      updated);
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
