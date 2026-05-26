package ru.fedukhin.edt.mcp.tools.md;

import jakarta.inject.Inject;
import java.util.ArrayList;
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
 * {@code add_dcs_total_field} — добавить {@code <totalField>} в .dcs root.
 *
 * <p>Args: {@code { project, reportFqn, templateName?, dataPath, expression, groupKeys? }}.
 * <ul>
 *   <li>{@code groupKeys} — массив имён полей-группировок (или пустой). Если передан как
 *       строка с разделителем «,» — splittится автоматически.</li>
 * </ul>
 * <p>Result: {@code { reportFqn, templateName, dcsPath, dataPath, added }}.
 *
 * <p>Идемпотентно по {@code dataPath}.
 */
public final class AddDcsTotalFieldTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final DcsFileEditor           editor;

    @Inject
    public AddDcsTotalFieldTool(DcsFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public AddDcsTotalFieldTool(Supplier<IWorkspaceRoot> rootSupplier, DcsFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_dcs_total_field"; }
    @Override public String description() {
        return "Stage 8f: append a <totalField> (aggregation) to Template.dcs root. Args: "
             + "reportFqn, templateName?, dataPath, expression (e.g. 'Сумма(X)' / 'Минимум(X)'), "
             + "groupKeys (array of group field names; may be empty). Idempotent on dataPath.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> arr = Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",      str);
        props.put("reportFqn",    str);
        props.put("templateName", str);
        props.put("dataPath",     str);
        props.put("expression",   str);
        props.put("groupKeys",    arr);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "reportFqn", "dataPath", "expression"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName  = requireString(args, "project");
        String reportFqn    = requireString(args, "reportFqn");
        String dataPath     = requireString(args, "dataPath");
        String expression   = requireString(args, "expression");
        String templateName = args.get("templateName") instanceof String t && !t.isEmpty()
                ? t : "ОсновнаяСхема";
        List<String> groupKeys = parseGroupKeys(args.get("groupKeys"));

        IFile dcsFile = AddDcsCalculatedFieldTool.resolveDcs(
                rootSupplier.get(), projectName, reportFqn, templateName);
        boolean added = editor.addTotalField(dcsFile, dataPath, expression, groupKeys);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportFqn",    reportFqn);
        result.put("templateName", templateName);
        result.put("dcsPath",      dcsFile.getProjectRelativePath().toString());
        result.put("dataPath",     dataPath);
        result.put("groupKeys",    groupKeys);
        result.put("added",        added);
        return result;
    }

    private static List<String> parseGroupKeys(Object v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        if (v instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof String s && !s.isEmpty()) out.add(s);
            }
        } else if (v instanceof String s && !s.isEmpty()) {
            for (String p : s.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
