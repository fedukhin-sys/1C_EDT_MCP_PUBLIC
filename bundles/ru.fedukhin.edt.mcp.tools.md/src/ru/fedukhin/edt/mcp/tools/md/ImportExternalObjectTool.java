package ru.fedukhin.edt.mcp.tools.md;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.ExternalObjectImporter;

/**
 * {@code import_external_object} — заводит готовый {@code .epf}/{@code .erf} в проект внешних
 * объектов, тем же путём, что мастер «Импорт → Внешние отчёты и обработки» в IDE.
 *
 * <p>Args: {@code { project, file, overwrite?, timeoutSeconds? }}
 * <p>Result: {@code { project, fqn, objectDir, durationMs, warnings[] }}
 *
 * <p>Версия платформы и базовый проект конфигурации в аргументах не нужны: и то и другое уже
 * задано проектом ({@code DT-INF/PROJECT.PMF}). Имя объекта задаёт сам файл — оно лежит в
 * {@code <Name>} выгрузки, а не в имени файла ({@code АРМ_150626.epf} даёт объект {@code АРМ}),
 * переименовать импортированный объект можно потом через {@code rename_md_object}.
 *
 * <p>Распаковка запускает платформу с ИБ проекта, поэтому у проекта должна быть связанная ИБ
 * ({@code associate_infobase}) — как и при {@code build_external_object}.
 */
public final class ImportExternalObjectTool implements IMcpTool {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".epf", ".erf");

    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int MIN_TIMEOUT_SECONDS     = 30;
    private static final int MAX_TIMEOUT_SECONDS     = 3600;

    private final Supplier<IWorkspaceRoot>          rootSupplier;
    private final Provider<ExternalObjectImporter>  importerProvider;

    @Inject
    public ImportExternalObjectTool(Provider<ExternalObjectImporter> importerProvider) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), importerProvider);
    }

    /** Test seam. */
    public ImportExternalObjectTool(Supplier<IWorkspaceRoot> rootSupplier,
                                    Provider<ExternalObjectImporter> importerProvider) {
        this.rootSupplier     = rootSupplier;
        this.importerProvider = importerProvider;
    }

    @Override public String name() { return "import_external_object"; }

    @Override public String description() {
        return "Import an existing .epf/.erf file into an external-object project — the same path "
             + "as 'Import external data processors or reports' in the IDE (EDT unpacks the file "
             + "into Designer XML and imports it as EDT sources). The platform version and the "
             + "base configuration project come from the project itself; the infobase associated "
             + "with the project is used for unpacking (see associate_infobase) and is locked for "
             + "the duration. The object name comes from the file's content, not from the file "
             + "name — rename afterwards with rename_md_object. Refuses to replace an existing "
             + "object of the same name unless overwrite=true.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("type", "string");
        project.put("description", "name of an existing open external-object project");

        Map<String, Object> file = new LinkedHashMap<>();
        file.put("type", "string");
        file.put("description", "path to the .epf/.erf file to import");

        Map<String, Object> overwrite = new LinkedHashMap<>();
        overwrite.put("type", "boolean");
        overwrite.put("description", "replace an object with the same name (default false)");

        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer");
        timeout.put("minimum", MIN_TIMEOUT_SECONDS);
        timeout.put("maximum", MAX_TIMEOUT_SECONDS);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",        project);
        props.put("file",           file);
        props.put("overwrite",      overwrite);
        props.put("timeoutSeconds", timeout);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "file"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        Path file          = requireFile(args);
        boolean overwrite  = optBoolean(args, "overwrite");
        int timeoutSeconds = optTimeout(args);

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        ExternalObjectImporter.ImportOutcome outcome = importer().importObject(
                new ExternalObjectImporter.ImportRequest(project, file, overwrite, timeoutSeconds));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project",    projectName);
        result.put("fqn",        outcome.fqn());
        result.put("objectDir",  outcome.objectDir());
        result.put("durationMs", outcome.durationMs());
        result.put("warnings",   outcome.warnings());
        return result;
    }

    /**
     * Импортёр создаётся лениво: он единственный в бандле трогает API импорта
     * ({@code IExternalObjectRestorer}, {@code IImportOperationFactory}). Плагин компилируется
     * против EDT 2026.x и обязан грузиться на 2023.x — если там этих классов нет, отказать
     * должен один инструмент, а не весь бандл.
     */
    private ExternalObjectImporter importer() throws ToolException {
        try {
            return importerProvider.get();
        } catch (LinkageError e) {
            throw new ToolException("импорт внешних объектов не поддерживается этой сборкой 1C:EDT: "
                + e, e);
        }
    }

    private static Path requireFile(Map<String, Object> args) throws ToolException {
        String raw = requireString(args, "file");
        Path file;
        try { file = Path.of(raw); }
        catch (InvalidPathException e) { throw new ToolException("'file' is not a valid path: " + raw, e); }

        String lower = file.getFileName() == null ? ""
                : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (SUPPORTED_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw new ToolException("'file' must be an .epf or .erf file: " + raw);
        }
        if (!Files.isRegularFile(file)) {
            throw new ToolException("file not found: " + file);
        }
        try {
            if (Files.size(file) == 0) throw new ToolException("file is empty: " + file);
        } catch (IOException e) {
            throw new ToolException("cannot read " + file + ": " + e.getMessage(), e);
        }
        return file;
    }

    private static boolean optBoolean(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (v == null) return false;
        if (!(v instanceof Boolean b)) throw new ToolException("'" + key + "' must be a boolean");
        return b;
    }

    private static int optTimeout(Map<String, Object> args) throws ToolException {
        Object v = args.get("timeoutSeconds");
        if (v == null) return DEFAULT_TIMEOUT_SECONDS;
        if (!(v instanceof Number n)) {
            throw new ToolException("'timeoutSeconds' must be a number");
        }
        int seconds = n.intValue();
        if (seconds < MIN_TIMEOUT_SECONDS || seconds > MAX_TIMEOUT_SECONDS) {
            throw new ToolException("'timeoutSeconds' must be within ["
                    + MIN_TIMEOUT_SECONDS + ", " + MAX_TIMEOUT_SECONDS + "]");
        }
        return seconds;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return s;
    }
}
