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
import ru.fedukhin.edt.mcp.tools.md.internal.DcsFileEditor;

/**
 * {@code add_dcs_setting_grouping} — добавить группировку по полю в settingsVariant'е .dcs.
 *
 * <p>Args: {@code { project, reportFqn, templateName?, variantName?, field, groupType? }}.
 * <ul>
 *   <li>{@code variantName} — имя settingsVariant'а (default {@code "Основной"}).</li>
 *   <li>{@code field} — DCS-поле для группировки (e.g. "Номенклатура").</li>
 *   <li>{@code groupType} — {@code Items}/{@code Hierarchy}/{@code HierarchyOnly} (default Items).</li>
 * </ul>
 * <p>Result: {@code { reportFqn, templateName, dcsPath, variantName, field, added }}.
 * Идемпотентно по {@code (variantName, field)}.
 */
public final class AddDcsSettingGroupingTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final DcsFileEditor           editor;

    @Inject
    public AddDcsSettingGroupingTool(DcsFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public AddDcsSettingGroupingTool(Supplier<IWorkspaceRoot> rootSupplier, DcsFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_dcs_setting_grouping"; }
    @Override public String description() {
        return "Append a GroupItemField to root StructureItemGroup of a settingsVariant in Template.dcs. "
             + "Args: reportFqn='Report.X', templateName (default 'ОсновнаяСхема'), "
             + "variantName (default 'Основной'), field, groupType (Items|Hierarchy|HierarchyOnly, "
             + "default Items). Idempotent on (variantName, field).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",      str);
        props.put("reportFqn",    str);
        props.put("templateName", str);
        props.put("variantName",  str);
        props.put("field",        str);
        props.put("groupType",    str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "reportFqn", "field"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName  = requireString(args, "project");
        String reportFqn    = requireString(args, "reportFqn");
        String field        = requireString(args, "field");
        String templateName = optString(args, "templateName", "ОсновнаяСхема");
        String variantName  = optString(args, "variantName",  "Основной");
        String groupType    = optString(args, "groupType",    "Items");

        if (!reportFqn.startsWith("Report.")) {
            throw new ToolException("reportFqn must be 'Report.<Name>', got: " + reportFqn);
        }
        String reportName = reportFqn.substring("Report.".length());

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String dcsRel = "src/Reports/" + reportName + "/Templates/" + templateName + "/Template.dcs";
        IFile dcsFile = project.getFile(dcsRel);
        if (!dcsFile.exists()) {
            throw new ToolException("Template.dcs not found at " + dcsRel);
        }

        boolean added = editor.addSettingsGrouping(dcsFile, variantName, field, groupType);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportFqn",    reportFqn);
        result.put("templateName", templateName);
        result.put("dcsPath",      dcsRel);
        result.put("variantName",  variantName);
        result.put("field",        field);
        result.put("groupType",    groupType);
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

    private static String optString(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return (v instanceof String s && !s.isEmpty()) ? s : def;
    }
}
