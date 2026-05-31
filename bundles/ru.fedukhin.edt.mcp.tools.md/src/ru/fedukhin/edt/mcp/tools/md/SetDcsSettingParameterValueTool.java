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
 * {@code set_dcs_setting_parameter_value} — переопределить значение параметра в settingsVariant.
 *
 * <p>Replace-or-add: если SettingsParameterValue с таким {@code parameterName} уже есть в
 * {@code <dcsset:dataParameters>}, обновляем его {@code <dcscor:value>}; иначе создаём новый.
 *
 * <p>Args: {@code { project, reportFqn, templateName?, variantName?, parameterName, value? }}.
 * <ul>
 *   <li>{@code variantName} — имя settingsVariant'а (default {@code "Основной"}).</li>
 *   <li>{@code parameterName} — имя параметра (должен соответствовать root {@code <parameter>}).</li>
 *   <li>{@code value} — текстовое значение; если опущен — {@code xsi:nil="true"} (untyped null).</li>
 * </ul>
 *
 * <p>Result: {@code { ..., changed }} ({@code changed=true} всегда, т.к. либо update либо add).
 */
public final class SetDcsSettingParameterValueTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final DcsFileEditor           editor;

    @Inject
    public SetDcsSettingParameterValueTool(DcsFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public SetDcsSettingParameterValueTool(Supplier<IWorkspaceRoot> rootSupplier, DcsFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "set_dcs_setting_parameter_value"; }
    @Override public String description() {
        return "Set (or add) a SettingsParameterValue in a settingsVariant of Template.dcs. "
             + "Args: reportFqn='Report.X', templateName (default 'ОсновнаяСхема'), "
             + "variantName (default 'Основной'), parameterName, value (optional — omitted ⇒ xsi:nil). "
             + "Replace-or-add semantics.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",       str);
        props.put("reportFqn",     str);
        props.put("templateName",  str);
        props.put("variantName",   str);
        props.put("parameterName", str);
        props.put("value",         str);
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
        String templateName  = optString(args, "templateName", "ОсновнаяСхема");
        String variantName   = optString(args, "variantName",  "Основной");
        // value: present-and-string → value; present-as-null → null (xsi:nil); absent → null too.
        String value = args.containsKey("value") && args.get("value") instanceof String s ? s : null;

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

        boolean changed = editor.setSettingsParameterValue(dcsFile, variantName, parameterName, value);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportFqn",     reportFqn);
        result.put("templateName",  templateName);
        result.put("dcsPath",       dcsRel);
        result.put("variantName",   variantName);
        result.put("parameterName", parameterName);
        result.put("changed",       changed);
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
