package ru.fedukhin.edt.mcp.tools.md;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * {@code add_tabular_section} — добавляет inline TabularSection в parent
 * MdObject (Catalog/Document/BusinessProcess/Task/ChartOfX).
 *
 * <p>Args: {@code { project, ownerFqn, name }}
 * <p>Result: {@code { ownerFqn, tabularSectionFqn, name, mdoPath }}
 *
 * <p>tabularSectionFqn возвращается для дальнейшего использования в
 * {@link AddTabularSectionAttributeTool} (column addition).
 */
public final class AddTabularSectionTool implements IMcpTool {

    /**
     * Kind'ы с табличными частями. Имя папки — из {@link MdObjectRegistry#folderName};
     * здесь только «у кого вообще есть ТЧ» (у регистров и перечислений их нет).
     */
    static final Set<String> KINDS_WITH_TABULAR_SECTIONS = Set.of(
            "Catalog", "Document", "BusinessProcess", "Task",
            "ChartOfAccounts", "ChartOfCalculationTypes", "ChartOfCharacteristicTypes");

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdoFileEditor            editor;

    @Inject
    public AddTabularSectionTool(MdoFileEditor editor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), editor);
    }

    public AddTabularSectionTool(Supplier<IWorkspaceRoot> rootSupplier, MdoFileEditor editor) {
        this.rootSupplier = rootSupplier;
        this.editor       = editor;
    }

    @Override public String name()        { return "add_tabular_section"; }
    @Override public String description() {
        return "Add a TabularSection to a parent MdObject (Catalog/Document/BusinessProcess/Task/ChartOfX). "
             + "Columns are added via add_tabular_section_attribute.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",  str);
        props.put("ownerFqn", str);
        props.put("name",     str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "ownerFqn", "name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String ownerFqn    = requireString(args, "ownerFqn");
        String tsName      = requireString(args, "name");

        int dot = ownerFqn.indexOf('.');
        if (dot <= 0 || dot == ownerFqn.length() - 1) {
            throw new ToolException("ownerFqn must be 'Kind.Name': '" + ownerFqn + "'");
        }
        String kind = ownerFqn.substring(0, dot);
        String ownerName = ownerFqn.substring(dot + 1);
        String folder = KINDS_WITH_TABULAR_SECTIONS.contains(kind)
                ? MdObjectRegistry.folderName(kind) : null;
        if (folder == null) {
            throw new ToolException("kind '" + kind + "' does not support tabular sections; supported: "
                    + KINDS_WITH_TABULAR_SECTIONS);
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        // Same fix as Stage 8c Task #10 для ModuleFileBootstrap: BmPersistentExecutor
        // пишет .mdo напрямую через FS bypass'я workspace cache. Без refreshLocal
        // следующий вызов после create_md_object получит mdoFile.exists()=false
        // даже если файл реально на диске (Stage 1.9.0 smoke 2026-05-18 показал).
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String mdoPath = "src/" + folder + "/" + ownerName + "/" + ownerName + ".mdo";
        IFile mdoFile = project.getFile(mdoPath);
        if (!mdoFile.exists()) {
            throw new ToolException("parent MdObject '.mdo' not found at " + mdoPath);
        }

        editor.addTabularSection(mdoFile, new MdoFileEditor.TabularSectionSpec(tsName));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ownerFqn",          ownerFqn);
        result.put("tabularSectionFqn", ownerFqn + ".TabularSection." + tsName);
        result.put("name",              tsName);
        result.put("mdoPath",           mdoPath);
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
