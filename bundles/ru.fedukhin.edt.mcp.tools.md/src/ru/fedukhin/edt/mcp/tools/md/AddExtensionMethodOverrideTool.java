package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import ru.fedukhin.edt.mcp.tools.md.internal.ExtensionModuleMerger;
import ru.fedukhin.edt.mcp.tools.md.internal.ObjectModuleGuard;

/**
 * {@code add_extension_method_override} — добавить annotated procedure
 * ({@code &ИзменениеИКонтроль}, {@code &Перед}, {@code &После}, и т.п.) в
 * extension's BSL-модуль (Stage 9c).
 *
 * <p>Args: {@code { project, modulePath, source }}
 * <ul>
 *   <li>{@code project} — extension project name.</li>
 *   <li>{@code modulePath} — относительный путь к .bsl файлу
 *       (e.g. {@code src/Documents/ЗаказКлиента/ObjectModule.bsl},
 *       {@code src/CommonModules/Контр_МойМодуль/Module.bsl},
 *       {@code src/Documents/ЗаказКлиента/Forms/ФормаДокумента/Module.bsl}).
 *       Файл и родительские папки будут созданы при необходимости.</li>
 *   <li>{@code source} — полный BSL-текст для добавления в конец модуля:
 *       аннотация (&amp;ИзменениеИКонтроль / &amp;Перед / &amp;После / &amp;Вместо)
 *       + {@code Процедура ...} + тело с {@code #Вставка/#КонецВставки} маркерами
 *       + {@code КонецПроцедуры}.</li>
 * </ul>
 *
 * <p>Result: {@code { project, modulePath, action, procName?, appendedBytes, totalBytes }}.
 * {@code action} — {@code "appended"} (новая процедура) или {@code "merged"} (тело
 * новой процедуры влито в конец уже существующей с тем же именем).
 *
 * <p>Tool парсит из {@code source} имя процедуры/функции (через
 * {@link ExtensionModuleMerger}), и если такая процедура уже есть в модуле —
 * инжектирует тело новой в конец существующей. Это покрывает кейс «дважды
 * {@code &После("ПриЗаписи")}», когда оба вызова генерят {@code Расш1_ПриЗаписи_После}
 * и EDT-компилятор валится «Метод уже определён».
 */
public final class AddExtensionMethodOverrideTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IV8ProjectManager projectManager;

    @Inject
    public AddExtensionMethodOverrideTool(IV8ProjectManager projectManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), projectManager);
    }

    public AddExtensionMethodOverrideTool(Supplier<IWorkspaceRoot> rootSupplier,
                                          IV8ProjectManager projectManager) {
        this.rootSupplier = rootSupplier;
        this.projectManager = projectManager;
    }

    @Override public String name()        { return "add_extension_method_override"; }
    @Override public String description() {
        return "Append a BSL procedure (with &ИзменениеИКонтроль/&Перед/&После/&Вместо annotation) "
             + "to an extension module. Creates file + parent folders if missing.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",    str);
        props.put("modulePath", str);
        props.put("source",     str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "modulePath", "source"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String modulePath  = requireString(args, "modulePath");
        String source      = requireString(args, "source");

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        IFile file = project.getFile(modulePath);

        try {
            // Ensure parent folders
            if (file.getParent() instanceof IFolder folder) {
                ensureFolder(folder);
            }

            // Read existing content (or empty if file doesn't exist)
            byte[] existing = file.exists() ? readAll(file.getContents()) : new byte[0];
            String existingText = new String(existing, StandardCharsets.UTF_8);

            // BUG-17: an object-side override method must carry the same #Если
            // preprocessor guard as the base module, otherwise EDT reports
            // «Метод расширения имеет большую видимость». Wrap it if needed.
            String effectiveSource = guardObjectModuleSource(project, modulePath, source);

            // Merge: либо append с blank-line separator, либо inject в существующую
            // процедуру с тем же именем (см. ExtensionModuleMerger).
            ExtensionModuleMerger.Result merged = ExtensionModuleMerger.merge(existingText, effectiveSource);
            byte[] bytes = merged.text().getBytes(StandardCharsets.UTF_8);

            if (file.exists()) {
                file.setContents(new ByteArrayInputStream(bytes), true, true, new NullProgressMonitor());
            } else {
                file.create(new ByteArrayInputStream(bytes), true, new NullProgressMonitor());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("project",       projectName);
            result.put("modulePath",    modulePath);
            result.put("action",        merged.action().name().toLowerCase());
            if (merged.procName() != null) result.put("procName", merged.procName());
            result.put("appendedBytes", effectiveSource.getBytes(StandardCharsets.UTF_8).length);
            result.put("totalBytes",    bytes.length);
            return result;

        } catch (CoreException | IOException e) {
            throw new ToolException("failed to append to " + modulePath + ": " + e.getMessage());
        }
    }

    /**
     * BUG-17: when {@code modulePath} is an object-side module, wraps {@code source}
     * in the base module's {@code #Если} guard so the override has the same
     * compilation visibility. Returns {@code source} unchanged when no guard is
     * needed or the base module cannot be resolved.
     */
    private String guardObjectModuleSource(IProject extProject, String modulePath, String source) {
        if (!ObjectModuleGuard.isGuardedModule(modulePath)
                || ObjectModuleGuard.alreadyGuarded(source)) {
            return source;
        }
        String[] guard = baseModuleGuard(extProject, modulePath);
        return guard == null ? source : ObjectModuleGuard.wrap(source, guard);
    }

    /**
     * Reads the parent configuration's module at the same {@code modulePath} and
     * detects its {@code #Если} guard. Returns {@code null} when the project is
     * not an extension, the base module is missing, or it is not guarded.
     */
    private String[] baseModuleGuard(IProject extProject, String modulePath) {
        try {
            IProject parentProject = resolveParentProject(extProject);
            if (parentProject == null || !parentProject.isAccessible()) {
                return null;
            }
            IFile baseFile = parentProject.getFile(modulePath);
            if (!baseFile.exists()) {
                return null;
            }
            String baseText = new String(readAll(baseFile.getContents()), StandardCharsets.UTF_8);
            return ObjectModuleGuard.detectGuard(baseText);
        } catch (CoreException | IOException | RuntimeException e) {
            return null;   // best-effort — fall back to appending the source as-is
        }
    }

    /** Resolves the parent configuration project of an extension project. */
    private IProject resolveParentProject(IProject extProject) {
        if (projectManager == null) {
            return null;
        }
        IV8Project v8 = projectManager.getProject(extProject);
        return (v8 instanceof IDependentProject dep) ? dep.getParentProject() : null;
    }

    private static void ensureFolder(IFolder folder) throws CoreException {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) ensureFolder(parent);
        folder.create(/*force*/ false, /*local*/ true, new NullProgressMonitor());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
