package ru.fedukhin.edt.mcp.tools.md;

import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.BuildPrecheck;
import ru.fedukhin.edt.mcp.tools.md.internal.ExternalObjectBuilder;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckMarker;
import ru.fedukhin.edt.mcp.tools.quality.internal.MarkerReader;

/**
 * {@code build_external_object} — собирает внешний объект проекта external-object
 * в {@code .epf}/{@code .erf}.
 *
 * <p>Args: {@code { project, fqn, outPath, timeoutSeconds? }}
 * <p>Result: {@code { epfPath, fqn, durationMs, warnings[] }}
 *
 * <p>Сборку делает штатный сервис EDT ({@code IExternalObjectDumper}) — тот же, что за
 * «Экспортом» в IDE: ИБ, учётку и версию платформы он берёт из приложения, ассоциированного
 * с проектом, поэтому в аргументах их нет.
 *
 * <p>Порядок шагов важен: сначала <b>precheck</b> по EDT-маркерам ({@link BuildPrecheck}),
 * и только потом сборка. Синтаксис BSL не проверяет ни выгрузка, ни платформа, поэтому без
 * precheck сломанный модуль молча оказался бы в готовом файле.
 *
 * <p>Маркеры читаются как есть (как в {@code check_list_markers}), без перезапуска проверок:
 * если валидация в проекте ни разу не отрабатывала, барьер пропустит всё — в этом случае
 * стоит сначала прогнать {@code check_run}.
 */
public final class BuildExternalObjectTool implements IMcpTool {

    /** kind → каталог в src/ и расширение результата. */
    private static final Map<String, String> KIND_FOLDER = Map.of(
            "ExternalDataProcessor", "ExternalDataProcessors",
            "ExternalReport",        "ExternalReports");

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MIN_TIMEOUT_SECONDS     = 30;
    private static final int MAX_TIMEOUT_SECONDS     = 3600;

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MarkerReader             markerReader;
    private final ExternalObjectBuilder    builder;

    @Inject
    public BuildExternalObjectTool(MarkerReader markerReader, ExternalObjectBuilder builder) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), markerReader, builder);
    }

    /** Test seam. */
    public BuildExternalObjectTool(Supplier<IWorkspaceRoot> rootSupplier, MarkerReader markerReader,
                                   ExternalObjectBuilder builder) {
        this.rootSupplier = rootSupplier;
        this.markerReader = markerReader;
        this.builder      = builder;
    }

    @Override public String name() { return "build_external_object"; }

    @Override public String description() {
        return "Build an external object (ExternalDataProcessor/ExternalReport) into an .epf/.erf file "
             + "using EDT's own export service — the same one behind 'Export' in the IDE. The infobase, "
             + "credentials and platform version come from the application associated with the project, "
             + "so the project must have one (see associate_infobase); the infobase is locked for the "
             + "duration of the build. Refuses to build while the project has BSL compile errors (same "
             + "markers as check_list_markers) — nothing else validates BSL syntax.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> fqnProp = new LinkedHashMap<>();
        fqnProp.put("type", "string");
        fqnProp.put("description", "'ExternalDataProcessor.<Name>' or 'ExternalReport.<Name>'");
        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer");
        timeout.put("minimum", MIN_TIMEOUT_SECONDS);
        timeout.put("maximum", MAX_TIMEOUT_SECONDS);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",        str);
        props.put("fqn",            fqnProp);
        props.put("outPath",        str);
        props.put("timeoutSeconds", timeout);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "fqn", "outPath"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");
        String outPath     = requireString(args, "outPath");
        int timeoutSeconds = optTimeout(args);

        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot == fqn.length() - 1) {
            throw new ToolException("fqn must be 'Kind.Name': '" + fqn + "'");
        }
        String kind   = fqn.substring(0, dot);
        String name   = fqn.substring(dot + 1);
        String folder = KIND_FOLDER.get(kind);
        if (folder == null) {
            throw new ToolException("kind '" + kind + "' is not buildable; supported: "
                    + KIND_FOLDER.keySet());
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String mdoPath = "src/" + folder + "/" + name + "/" + name + ".mdo";
        if (!project.getFile(mdoPath).exists()) {
            throw new ToolException("external object '" + fqn + "' not found: no .mdo at " + mdoPath);
        }

        BuildPrecheck.Verdict verdict = BuildPrecheck.of(
                markerReader.read(project, null, null, null, null));
        if (!verdict.blockers().isEmpty()) {
            throw new ToolException(blockerMessage(projectName, verdict.blockers()));
        }

        ExternalObjectBuilder.BuildOutcome outcome = builder.build(
                new ExternalObjectBuilder.BuildRequest(project, fqn, Path.of(outPath),
                        timeoutSeconds));

        List<String> warnings = new ArrayList<>(verdict.warnings());
        warnings.addAll(outcome.warnings());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("epfPath",    outcome.epfPath().toString());
        result.put("fqn",        fqn);
        result.put("durationMs", outcome.durationMs());
        result.put("warnings",   List.copyOf(warnings));
        return result;
    }

    private static String blockerMessage(String projectName, List<CheckMarker> blockers) {
        StringBuilder sb = new StringBuilder("precheck failed: project '").append(projectName)
                .append("' has ").append(blockers.size())
                .append(" BSL compile error(s); the build would silently pack broken code into the "
                        + "output file:");
        for (CheckMarker m : blockers) {
            sb.append("\n  - ").append(BuildPrecheck.format(m));
        }
        return sb.append("\nFix them (full list: check_list_markers) and retry.").toString();
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
        return (String) v;
    }
}
