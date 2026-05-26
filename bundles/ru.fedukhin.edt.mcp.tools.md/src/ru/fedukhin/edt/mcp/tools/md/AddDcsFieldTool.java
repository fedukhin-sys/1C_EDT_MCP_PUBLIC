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
 * {@code add_dcs_field} — Stage 8f sub-tool: добавить {@code <field xsi:type="DataSetFieldField">}
 * внутрь указанного dataSet'а в .dcs.
 *
 * <p>Args: {@code { project, reportFqn, templateName?, dataSetName, fieldName, title? }}.
 * <p>Result: {@code { reportFqn, templateName, dcsPath, dataSetName, fieldName, added }}.
 *
 * <p>Идемпотентно: повторно тот же fieldName в том же dataSet'е не дублируется.
 */
public final class AddDcsFieldTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final DcsFileEditor           editor;

    @Inject
    public AddDcsFieldTool(DcsFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public AddDcsFieldTool(Supplier<IWorkspaceRoot> rootSupplier, DcsFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_dcs_field"; }
    @Override public String description() {
        return "Stage 8f: append a <field xsi:type=\"DataSetFieldField\"> into an existing dataSet "
             + "inside Template.dcs. Args: reportFqn='Report.X', templateName (default 'ОсновнаяСхема'), "
             + "dataSetName, fieldName, title (optional). Idempotent on (dataSetName, fieldName).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",      str);
        props.put("reportFqn",    str);
        props.put("templateName", str);
        props.put("dataSetName",  str);
        props.put("fieldName",    str);
        props.put("title",        str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "reportFqn", "dataSetName", "fieldName"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName  = requireString(args, "project");
        String reportFqn    = requireString(args, "reportFqn");
        String dataSetName  = requireString(args, "dataSetName");
        String fieldName    = requireString(args, "fieldName");
        String title        = args.get("title") instanceof String s && !s.isEmpty() ? s : null;
        String templateName = args.get("templateName") instanceof String t && !t.isEmpty()
                ? t : "ОсновнаяСхема";

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

        boolean added = editor.addDataSetField(dcsFile, dataSetName, fieldName, title);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportFqn",    reportFqn);
        result.put("templateName", templateName);
        result.put("dcsPath",      dcsRel);
        result.put("dataSetName",  dataSetName);
        result.put("fieldName",    fieldName);
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
