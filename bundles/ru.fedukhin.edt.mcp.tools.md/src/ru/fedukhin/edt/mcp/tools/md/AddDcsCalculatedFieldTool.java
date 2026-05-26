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
 * {@code add_dcs_calculated_field} — добавить {@code <calculatedField>} в .dcs root.
 *
 * <p>Args: {@code { project, reportFqn, templateName?, dataPath, expression, title? }}.
 * <p>Result: {@code { reportFqn, templateName, dcsPath, dataPath, added }}.
 *
 * <p>Идемпотентно по {@code dataPath}.
 */
public final class AddDcsCalculatedFieldTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final DcsFileEditor           editor;

    @Inject
    public AddDcsCalculatedFieldTool(DcsFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public AddDcsCalculatedFieldTool(Supplier<IWorkspaceRoot> rootSupplier, DcsFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_dcs_calculated_field"; }
    @Override public String description() {
        return "Stage 8f: append a <calculatedField> to Template.dcs root. Args: reportFqn='Report.X', "
             + "templateName (default 'ОсновнаяСхема'), dataPath, expression (1C calc formula), "
             + "title (optional ru-localized). Idempotent on dataPath.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",      str);
        props.put("reportFqn",    str);
        props.put("templateName", str);
        props.put("dataPath",     str);
        props.put("expression",   str);
        props.put("title",        str);
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
        String title        = args.get("title") instanceof String s && !s.isEmpty() ? s : null;
        String templateName = args.get("templateName") instanceof String t && !t.isEmpty()
                ? t : "ОсновнаяСхема";

        IFile dcsFile = resolveDcs(rootSupplier.get(), projectName, reportFqn, templateName);
        boolean added = editor.addCalculatedField(dcsFile, dataPath, expression, title);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportFqn",    reportFqn);
        result.put("templateName", templateName);
        result.put("dcsPath",      dcsFile.getProjectRelativePath().toString());
        result.put("dataPath",     dataPath);
        result.put("added",        added);
        return result;
    }

    static IFile resolveDcs(IWorkspaceRoot root, String projectName, String reportFqn,
                            String templateName) throws ToolException {
        if (!reportFqn.startsWith("Report.")) {
            throw new ToolException("reportFqn must be 'Report.<Name>', got: " + reportFqn);
        }
        String reportName = reportFqn.substring("Report.".length());
        IProject project = root.getProject(projectName);
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
        return dcsFile;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
