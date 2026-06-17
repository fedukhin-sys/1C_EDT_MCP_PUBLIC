package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * {@code create_external_object} — создаёт новый внешний объект
 * (ExternalDataProcessor / ExternalReport) в существующем проекте внешних объектов
 * (nature {@code V8ExternalObjectsNature}).
 *
 * <p>Реализация <b>файловая</b> (не BM-транзакция): у внешних объектов нет
 * Configuration-контейнера, а EDT API {@code IExternalObjectProjectManager.create}
 * умеет создавать только новый ПРОЕКТ с объектом-семенем, не «добавить объект в
 * существующий проект». Объекты обнаруживаются сканированием
 * {@code src/ExternalDataProcessors/} / {@code src/ExternalReports/}.
 *
 * <p>Пишет:
 * <ul>
 *   <li>{@code src/<Folder>/<Name>/<Name>.mdo} — skeleton с producedTypes/objectType,
 *       name, synonym?, comment?, containedObjects (classId самого MdClass);</li>
 *   <li>{@code src/<Folder>/<Name>/ObjectModule.bsl} — пустой модуль объекта.</li>
 * </ul>
 *
 * <p>Args: {@code { project, kind, name, synonym?, comment? }} где
 * {@code kind ∈ {ExternalDataProcessor, ExternalReport}}.
 * <p>Result: {@code { fqn, kind, name, mdoPath, modulePath }}.
 */
public final class CreateExternalObjectTool implements IMcpTool {

    /** classId соответствующего MdClass (см. MdClass.xcore @MdClass(^id=...)). */
    private static final String CLASS_ID_DATA_PROCESSOR = "c3831ec8-d8d5-4f93-8a22-f9bfae07327f";
    private static final String CLASS_ID_REPORT         = "e41aff26-25cf-4bb6-b6c1-3f478a75f374";

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IBmModelManager          bmModelManager;

    @Inject
    public CreateExternalObjectTool(IBmModelManager bmModelManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), bmModelManager);
    }

    /** Test seam. */
    public CreateExternalObjectTool(Supplier<IWorkspaceRoot> rootSupplier) {
        this(rootSupplier, null);
    }

    public CreateExternalObjectTool(Supplier<IWorkspaceRoot> rootSupplier, IBmModelManager bmModelManager) {
        this.rootSupplier   = rootSupplier;
        this.bmModelManager = bmModelManager;
    }

    @Override public String name()        { return "create_external_object"; }
    @Override public String description() {
        return "Create a new external object (ExternalDataProcessor or ExternalReport) in an "
             + "external-object project. File-based: writes src/<Folder>/<Name>/<Name>.mdo "
             + "(producedTypes + containedObjects skeleton) and an empty ObjectModule.bsl.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> kindProp = new LinkedHashMap<>();
        kindProp.put("type", "string");
        kindProp.put("enum", List.of("ExternalDataProcessor", "ExternalReport"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", str);
        props.put("kind",    kindProp);
        props.put("name",    str);
        props.put("synonym", str);
        props.put("comment", str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "kind", "name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String kind        = requireString(args, "kind");
        String name        = requireString(args, "name");
        String synonym     = args.get("synonym") instanceof String s && !s.isEmpty() ? s : null;
        String comment     = args.get("comment") instanceof String s && !s.isEmpty() ? s : null;

        String folder;
        String classId;
        switch (kind) {
            case "ExternalDataProcessor" -> { folder = "ExternalDataProcessors"; classId = CLASS_ID_DATA_PROCESSOR; }
            case "ExternalReport"        -> { folder = "ExternalReports";         classId = CLASS_ID_REPORT; }
            default -> throw new ToolException("kind must be ExternalDataProcessor or ExternalReport, got: " + kind);
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String objDirRel  = "src/" + folder + "/" + name;
        String mdoRel     = objDirRel + "/" + name + ".mdo";
        String moduleRel  = objDirRel + "/ObjectModule.bsl";
        IFile mdoFile     = project.getFile(mdoRel);
        if (mdoFile.exists()) {
            throw new ToolException("external object '" + kind + "." + name
                    + "' already exists at " + mdoRel);
        }

        String mdoXml = buildMdo(kind, classId, name, synonym, comment);

        try {
            ensureFolder(project.getFolder(objDirRel));
            mdoFile.create(new ByteArrayInputStream(mdoXml.getBytes(StandardCharsets.UTF_8)),
                    /*force*/ false, new NullProgressMonitor());
            IFile moduleFile = project.getFile(moduleRel);
            if (!moduleFile.exists()) {
                moduleFile.create(new ByteArrayInputStream(new byte[0]), false, new NullProgressMonitor());
            }
        } catch (CoreException e) {
            throw new ToolException("failed to create external object '" + name + "': " + e.getMessage());
        }

        if (bmModelManager != null) {
            try { bmModelManager.waitModelSynchronization(project); }
            catch (Throwable ignored) { /* best-effort */ }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn",        kind + "." + name);
        result.put("kind",       kind);
        result.put("name",       name);
        result.put("mdoPath",    mdoRel);
        result.put("modulePath", moduleRel);
        return result;
    }

    /** Строит skeleton .mdo внешнего объекта (порядок элементов — канон EDT). */
    static String buildMdo(String kind, String classId, String name, String synonym, String comment) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<mdclass:").append(kind)
          .append(" xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"")
          .append(UUID.randomUUID()).append("\">\n");
        sb.append("  <producedTypes>\n");
        sb.append("    <objectType typeId=\"").append(UUID.randomUUID())
          .append("\" valueTypeId=\"").append(UUID.randomUUID()).append("\"/>\n");
        sb.append("  </producedTypes>\n");
        sb.append("  <name>").append(esc(name)).append("</name>\n");
        if (synonym != null) {
            sb.append("  <synonym>\n    <key>ru</key>\n    <value>").append(esc(synonym))
              .append("</value>\n  </synonym>\n");
        }
        if (comment != null) {
            sb.append("  <comment>").append(esc(comment)).append("</comment>\n");
        }
        sb.append("  <containedObjects classId=\"").append(classId)
          .append("\" objectId=\"").append(UUID.randomUUID()).append("\"/>\n");
        sb.append("</mdclass:").append(kind).append(">\n");
        return sb.toString();
    }

    private static void ensureFolder(IFolder folder) throws CoreException {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) ensureFolder(parent);
        folder.create(/*force*/ false, /*local*/ true, new NullProgressMonitor());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
