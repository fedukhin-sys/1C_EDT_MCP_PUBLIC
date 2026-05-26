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
 * {@code add_dcs_parameter} — Stage 8f sub-tool: добавить {@code <parameter>} в .dcs.
 *
 * <p>Args: {@code { project, reportFqn, templateName?, parameterName, valueType?, title? }}.
 * <ul>
 *   <li>{@code valueType} — XSD-имя ({@code xs:boolean}/{@code xs:string}/{@code xs:dateTime}/
 *       {@code xs:decimal}) или 1С-тип (например {@code v8:CatalogRef.X}). Если пропущен —
 *       параметр untyped (xsi:nil="true").</li>
 * </ul>
 *
 * <p>Result: {@code { reportFqn, templateName, dcsPath, parameterName, added }}.
 *
 * <p>Идемпотентно: параметр с таким именем не дублируется.
 */
public final class AddDcsParameterTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final DcsFileEditor           editor;

    @Inject
    public AddDcsParameterTool(DcsFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public AddDcsParameterTool(Supplier<IWorkspaceRoot> rootSupplier, DcsFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_dcs_parameter"; }
    @Override public String description() {
        return "Stage 8f: append a <parameter> to Template.dcs. Args: reportFqn='Report.X', "
             + "templateName (default 'ОсновнаяСхема'), parameterName, valueType (XSD/v8 type, "
             + "optional), title (optional). Idempotent on parameterName.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",       str);
        props.put("reportFqn",     str);
        props.put("templateName",  str);
        props.put("parameterName", str);
        props.put("valueType",     str);
        props.put("title",         str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "reportFqn", "parameterName"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName   = requireString(args, "project");
        String reportFqn     = requireString(args, "reportFqn");
        String parameterName = requireString(args, "parameterName");
        String valueType     = args.get("valueType") instanceof String v && !v.isEmpty() ? v : null;
        String title         = args.get("title")     instanceof String t && !t.isEmpty() ? t : null;
        String templateName  = args.get("templateName") instanceof String n && !n.isEmpty()
                ? n : "ОсновнаяСхема";

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

        boolean added = editor.addParameter(dcsFile, parameterName, valueType, title);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportFqn",     reportFqn);
        result.put("templateName",  templateName);
        result.put("dcsPath",       dcsRel);
        result.put("parameterName", parameterName);
        result.put("added",         added);
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
