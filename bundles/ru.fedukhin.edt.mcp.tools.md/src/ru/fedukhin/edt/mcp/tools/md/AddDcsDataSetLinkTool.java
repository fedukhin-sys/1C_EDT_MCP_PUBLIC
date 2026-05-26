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
 * {@code add_dcs_dataset_link} — добавить {@code <dataSetLink>} (master-detail связь)
 * между двумя DataSet'ами в .dcs.
 *
 * <p>Args: {@code { project, reportFqn, templateName?, source, destination,
 * sourceExpression, destinationExpression, parameter? }}.
 *
 * <p>Result: {@code { reportFqn, templateName, dcsPath, source, destination, added }}.
 *
 * <p>Идемпотентно по 4-tuple {@code (source, destination, sourceExpression, destinationExpression)}.
 * Если {@code parameter} задан — пишется также {@code <parameterListAllowed>false</parameterListAllowed>}.
 */
public final class AddDcsDataSetLinkTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final DcsFileEditor           editor;

    @Inject
    public AddDcsDataSetLinkTool(DcsFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public AddDcsDataSetLinkTool(Supplier<IWorkspaceRoot> rootSupplier, DcsFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_dcs_dataset_link"; }
    @Override public String description() {
        return "Stage 8f: append a <dataSetLink> (master-detail) between two DataSets in Template.dcs. "
             + "Args: reportFqn, templateName?, source (name of source DataSet), destination (name of "
             + "destination DataSet), sourceExpression, destinationExpression, parameter? (optional bind "
             + "param). Both DataSets must already exist. Idempotent on (source,dest,srcExpr,destExpr).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",               str);
        props.put("reportFqn",             str);
        props.put("templateName",          str);
        props.put("source",                str);
        props.put("destination",           str);
        props.put("sourceExpression",      str);
        props.put("destinationExpression", str);
        props.put("parameter",             str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required",
                List.of("project", "reportFqn", "source", "destination",
                        "sourceExpression", "destinationExpression"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName     = requireString(args, "project");
        String reportFqn       = requireString(args, "reportFqn");
        String source          = requireString(args, "source");
        String destination     = requireString(args, "destination");
        String sourceExpr      = requireString(args, "sourceExpression");
        String destinationExpr = requireString(args, "destinationExpression");
        String parameter       = args.get("parameter") instanceof String p && !p.isEmpty() ? p : null;
        String templateName    = args.get("templateName") instanceof String t && !t.isEmpty()
                ? t : "ОсновнаяСхема";

        IFile dcsFile = AddDcsCalculatedFieldTool.resolveDcs(
                rootSupplier.get(), projectName, reportFqn, templateName);
        boolean added = editor.addDataSetLink(dcsFile, source, destination, sourceExpr,
                                              destinationExpr, parameter);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportFqn",    reportFqn);
        result.put("templateName", templateName);
        result.put("dcsPath",      dcsFile.getProjectRelativePath().toString());
        result.put("source",       source);
        result.put("destination",  destination);
        result.put("added",        added);
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
