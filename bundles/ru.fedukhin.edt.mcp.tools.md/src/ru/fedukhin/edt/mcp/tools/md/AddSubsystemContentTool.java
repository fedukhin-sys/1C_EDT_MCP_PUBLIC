package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
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
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * {@code add_subsystem_content} — добавляет ссылку на объект (Catalog/Document/Register/...)
 * в content одной Subsystem (command-interface binding).
 *
 * <p>Args: {@code { project, subsystemFqn, contentFqn }}.
 * <ul>
 *   <li>{@code subsystemFqn} — fqn:
 *     <ul>
 *       <li>{@code "Subsystem.X"} → {@code src/Subsystems/X/X.mdo} (top-level).</li>
 *       <li>{@code "Subsystem.X.Y"} → {@code src/Subsystems/X/Subsystems/Y/Y.mdo} (1 уровень nesting).</li>
 *       <li>{@code "Subsystem.X.Y.Z"} → {@code src/Subsystems/X/Subsystems/Y/Subsystems/Z/Z.mdo} (deep).</li>
 *     </ul>
 *     В оригинале extension'а Subsystem обычно уже adopted через {@code borrow_md_object}.</li>
 *   <li>{@code contentFqn} — fqn объекта для добавления в content (e.g. {@code "Catalog.X"}).</li>
 * </ul>
 * <p>Result: {@code { subsystem, content, mdoPath, added }}.
 *
 * <p>Идемпотентно. Если такая запись уже есть — {@code added=false}.
 */
public final class AddSubsystemContentTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdoFileEditor           mdoEditor;
    private final IBmModelManager         bmModelManager;

    @Inject
    public AddSubsystemContentTool(MdoFileEditor mdoEditor, IBmModelManager bmModelManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), mdoEditor, bmModelManager);
    }

    /** Test seam. */
    public AddSubsystemContentTool(Supplier<IWorkspaceRoot> rootSupplier, MdoFileEditor mdoEditor) {
        this(rootSupplier, mdoEditor, null);
    }

    public AddSubsystemContentTool(Supplier<IWorkspaceRoot> rootSupplier,
                                    MdoFileEditor mdoEditor, IBmModelManager bmModelManager) {
        this.rootSupplier   = rootSupplier;
        this.mdoEditor      = mdoEditor;
        this.bmModelManager = bmModelManager;
    }

    @Override public String name()        { return "add_subsystem_content"; }
    @Override public String description() {
        return "Add an MdObject reference to a Subsystem's <content> list (command-interface binding); idempotent.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",      str);
        props.put("subsystemFqn", str);
        props.put("contentFqn",   str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "subsystemFqn", "contentFqn"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName  = requireString(args, "project");
        String subsystemFqn = requireString(args, "subsystemFqn");
        String contentFqn   = requireString(args, "contentFqn");

        if (!subsystemFqn.startsWith("Subsystem.")) {
            throw new ToolException("subsystemFqn must be 'Subsystem.<Name>[.<Sub>...]', got: " + subsystemFqn);
        }
        String subsystemPath = subsystemFqn.substring("Subsystem.".length());
        if (subsystemPath.isEmpty() || contentFqn.indexOf('.') <= 0) {
            throw new ToolException("invalid fqn(s): subsystem='" + subsystemFqn
                    + "', content='" + contentFqn + "'");
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String mdoPath = subsystemMdoPath(subsystemPath);
        IFile mdoFile = project.getFile(mdoPath);
        if (!mdoFile.exists()) {
            throw new ToolException("subsystem .mdo not found at " + mdoPath);
        }

        boolean added = mdoEditor.addSubsystemContent(mdoFile, contentFqn);

        if (bmModelManager != null) {
            try { bmModelManager.waitModelSynchronization(project); }
            catch (Throwable ignored) { /* best-effort */ }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subsystem", subsystemFqn);
        result.put("content",   contentFqn);
        result.put("mdoPath",   mdoPath);
        result.put("added",     added);
        return result;
    }

    /**
     * Build .mdo path для subsystem, поддерживая nested:
     * <ul>
     *   <li>{@code "X"} → {@code src/Subsystems/X/X.mdo}</li>
     *   <li>{@code "X.Y"} → {@code src/Subsystems/X/Subsystems/Y/Y.mdo}</li>
     *   <li>{@code "X.Y.Z"} → {@code src/Subsystems/X/Subsystems/Y/Subsystems/Z/Z.mdo}</li>
     * </ul>
     */
    static String subsystemMdoPath(String subsystemPath) {
        String[] parts = subsystemPath.split("\\.");
        StringBuilder sb = new StringBuilder("src");
        for (String p : parts) {
            sb.append("/Subsystems/").append(p);
        }
        sb.append('/').append(parts[parts.length - 1]).append(".mdo");
        return sb.toString();
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
