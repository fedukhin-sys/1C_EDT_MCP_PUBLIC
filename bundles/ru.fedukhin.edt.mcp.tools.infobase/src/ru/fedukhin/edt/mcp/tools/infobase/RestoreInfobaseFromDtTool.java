package ru.fedukhin.edt.mcp.tools.infobase;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.core.resources.IProject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.jobs.JobContext;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.core.jobs.StepStatus;
import ru.fedukhin.edt.mcp.tools.infobase.internal.AfterLoad;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SchemaBuilder;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ToolArgs;

/**
 * Сценарий 3: связанная с проектом база загружается из {@code .dt}, затем проект конфигурации и его
 * проекты расширений обновляются из неё.
 *
 * <p>Проекты расширений выбираются по {@link InfobaseTargets#belongsTo}: в EDT 2026.1 база связана только
 * с проектом конфигурации, а проекты расширений своей связи не имеют и следуют за ним. Проект
 * расширения, связанный только с другими базами, не трогается и перечисляется в
 * {@code extensionProjectsOfOtherInfobases}.
 */
public class RestoreInfobaseFromDtTool implements IMcpTool {

    static final int DEFAULT_TIMEOUT_MINUTES = 180;

    private final InfobaseTargets targets;
    private final SyncV2 syncV2;
    private final ProjectFromInfobaseUpdater updater;
    private final ThickClientOps ops;
    private final InfobaseJobs jobs;

    @Inject
    public RestoreInfobaseFromDtTool(InfobaseTargets targets, SyncV2 syncV2, ProjectFromInfobaseUpdater updater,
                                     ThickClientOps ops, InfobaseJobs jobs) {
        this.targets = targets;
        this.syncV2 = syncV2;
        this.updater = updater;
        this.ops = ops;
        this.jobs = jobs;
    }

    @Override public String name() { return "restore_infobase_from_dt"; }

    @Override public String description() {
        return "Load a .dt file into the infobase associated with the configuration project, REPLACING ALL "
            + "infobase data, then pull the resulting configuration into the project and into its extension "
            + "projects (project content is replaced with the infobase version; objects deleted in the infobase "
            + "are deleted from the project). Extension projects follow the parent configuration's infobase; one "
            + "associated only with another infobase is skipped and listed in extensionProjectsOfOtherInfobases. "
            + "Use backupTo to dump the current infobase first. Refuses while these projects have changes not "
            + "deployed to the infobase, or EDT has no synchronization state for them (e.g. a new project), "
            + "unless discardProjectChanges=true. Background job: returns jobId, poll get_job_status. Requires "
            + "1C:EDT 2026.1+.";
    }

    @Override public Map<String, Object> inputSchema() {
        return SchemaBuilder.object()
            .string("project", "Configuration project whose infobase is restored", true)
            .string("dtFile", "Absolute path to the .dt file", true)
            .string("infobase", "Infobase name; default is the project's default associated infobase", false)
            .bool("updateExtensionProjects", "Also update the extension projects of this configuration: those without "
                + "their own infobase association or associated with this infobase (default true)")
            .string("backupTo", "Absolute path of a new .dt file: dump the current infobase there before restoring", false)
            .string("user", "Infobase user for the designer (as in the .dt); saved to EDT access settings", false)
            .string("password", "Password of the infobase user", false)
            .bool("discardProjectChanges", "Full replacement of every updated project: the whole configuration is "
                + "exported from the infobase, whatever it does not have is deleted from the project, even if the "
                + "projects have changes not deployed to the infobase or no synchronization state yet (a new project)")
            .bool("allowForeignInfobase", "Allow an infobase the project is not associated with")
            .integer("timeoutMinutes", "Job time limit, minutes (default 180)", 1, 1440)
            .build();
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = ToolArgs.required(args, "project");
        IProject project = targets.openProject(projectName);
        targets.configurationProject(project);
        Path dtFile = ToolArgs.existingFile("dtFile", ToolArgs.required(args, "dtFile"), "dt");
        Path backup = backupPath(ToolArgs.optional(args, "backupTo"));
        boolean updateExtensions = ToolArgs.flag(args, "updateExtensionProjects", true);
        syncV2.requireAvailable();
        InfobaseTargets.Target target = targets.resolve(project, ToolArgs.optional(args, "infobase"),
            ToolArgs.flag(args, "allowForeignInfobase", false));
        InfobaseReference infobase = target.infobase();
        List<IExtensionProject> extensionProjects = new ArrayList<>();
        List<String> ofOtherInfobases = new ArrayList<>();
        if (updateExtensions) {
            for (IExtensionProject ext : targets.extensionProjects(project)) {
                if (targets.belongsTo(ext.getProject(), infobase)) {
                    extensionProjects.add(ext);
                } else {
                    ofOtherInfobases.add(ext.getProject().getName());
                }
            }
        }
        boolean discard = ToolArgs.flag(args, "discardProjectChanges", false);
        List<IProject> overwritten = new ArrayList<>();
        overwritten.add(project);
        extensionProjects.forEach(ext -> overwritten.add(ext.getProject()));
        // I2 (fix round 9): отказ ДО загрузки — свой текст, без совета deploy_project: загрузка .dt заменила бы
        // залитое, а затем и проект — версией из базы. Выход — сохранить правки (git, backupTo) и повторить с флагом.
        Function<List<String>, String> refusal = atRisk -> SyncV2.atRiskBeforeLoadMessage(
            "загрузка .dt «" + dtFile.getFileName() + "» заменит всю конфигурацию и данные базы " + infobase.getName(),
            atRisk, backup == null);
        if (!discard) {
            // Быстрый отказ в очевидных случаях; окончательная проверка — сразу после ожидания фоновых проверок EDT
            // (check-projects), после ожидания модели EDT: свежую правку эта ещё не видит.
            List<String> atRisk = syncV2.projectsAtRisk(overwritten, infobase);
            if (!atRisk.isEmpty()) throw new ToolException(refusal.apply(atRisk));
        }
        String user = ToolArgs.optional(args, "user");
        String password = ToolArgs.optional(args, "password");
        Duration timeout = Duration.ofMinutes(
            ToolArgs.integer(args, "timeoutMinutes", DEFAULT_TIMEOUT_MINUTES, 1, 1440));

        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("project", projectName);
        echo.put("infobase", infobase.getName());
        echo.put("dtFile", dtFile.toString());
        if (backup != null) echo.put("backupTo", backup.toString());
        if (target.warning() != null) echo.put("warning", target.warning());

        return jobs.start(name(), "база " + infobase.getName() + " из " + dtFile.getFileName(), infobase,
            projectName, timeout, ctx -> run(ctx, project, infobase, dtFile, backup, updateExtensions,
                extensionProjects, ofOtherInfobases, user, password, discard, discard ? List.of() : overwritten,
                refusal), echo);
    }

    /**
     * @param discard {@code discardProjectChanges} вызова — доходит до обновления каждого проекта как есть (fix
     *     round 6, F4): загрузка {@code .dt} состояние синхронизации EDT НЕ сбрасывает, а её быстрая проверка по
     *     идентификатору поколения данных её не замечает. {@code false} — проект проверяется после ожидания модели
     *     (правка, сделанная во время загрузки, не перезаписывается: отказ со своим текстом — .dt уже загружен,
     *     {@code deploy_project} сейчас не вызывать, fix round 6b), затем закрывается сеанс агента и сбрасывается
     *     быстрая проверка — и EDT сравнивает базу с записанным состоянием, если оба шага удались (не удались —
     *     «изменений нет» приходит с предупреждением; fix round 7); {@code true} — полная замена
     * @param toCheck проекты, которые задание проверяет заново перед тем, как тронуть базу (шаг
     *     {@code check-projects}); пусто — {@code discardProjectChanges}
     * @param refusal текст отказа {@code check-projects} по его пунктам — тот же, что при вызове (I2)
     */
    private void run(JobContext ctx, IProject project, InfobaseReference infobase, Path dtFile, Path backup,
                     boolean updateExtensions, List<IExtensionProject> extensionProjects,
                     List<String> ofOtherInfobases, String user, String password, boolean discard,
                     List<IProject> toCheck, Function<List<String>, String> refusal) throws Exception {
        ctx.put("project", project.getName());
        ctx.put("infobase", infobase.getName());
        if (!toCheck.isEmpty()) InfobaseJobs.checkProjects(ctx, updater, toCheck, infobase, refusal);
        if (backup != null) {
            ctx.step("backup", () -> {
                ops.backupDt(project, infobase, backup, ctx.monitor());
                return null;
            });
            ctx.put("backup", backup.toString());
        }
        // .dt мог прийти из рабочей базы: флаг ПДн — в fail-closed ДО загрузки, чтобы прерванная
        // загрузка не оставила false.
        InfobaseJobs.resetPiiFlag(ctx, infobase.getName(), infobase.getUuid());
        ctx.step("restore-dt", () -> {
            ops.restoreDt(project, infobase, dtFile, ctx.monitor());
            return null;
        });
        if (user != null) {
            ctx.step("access-settings", () -> {
                ops.storeCredentials(infobase, user, password);
                return null;
            });
        }

        // m2 (fix round 9): всё, чем кончится обновление проекта после загрузки .dt, — и отказ, и сбой, и
        // предупреждение — несёт указание «после загрузки»: .dt уже загружен, deploy_project сейчас не вызывать, как
        // забрать базу в проект. Для проекта конфигурации — ещё и какие проекты расширений задание не обновило.
        String dtLoaded = "файл .dt «" + dtFile.getFileName() + "» уже загружен в базу " + infobase.getName();
        List<Map<String, Object>> projects = new ArrayList<>();
        ProjectFromInfobaseUpdater.Outcome outcome = InfobaseJobs.updateProject(ctx, updater, project, infobase,
            discard, new AfterLoad(dtLoaded, extensionProjects.stream().map(ext -> ext.getProject().getName())
                .toList()));
        projects.add(updated(project.getName(), "configuration", outcome));

        if (updateExtensions) {
            List<String> inInfobase = ctx.step("list-extensions",
                () -> ops.listExtensions(project, infobase, ctx.monitor()));
            Set<String> present = new HashSet<>();
            inInfobase.forEach(name -> present.add(upper(name)));
            Set<String> covered = new HashSet<>();
            List<String> projectsWithoutExtension = new ArrayList<>();
            for (IExtensionProject ext : extensionProjects) {
                IProject extProject = ext.getProject();
                String extName = InfobaseTargets.extensionName(ext);
                if (extName == null || !present.contains(upper(extName))) {
                    String reason = extName == null
                        ? "имя расширения проекта " + extProject.getName() + " не определено (модель проекта ещё "
                            + "не загружена)"
                        : "расширения '" + extName + "' нет в базе после загрузки .dt";
                    ctx.record("update-project " + extProject.getName(), StepStatus.SKIPPED, reason);
                    projectsWithoutExtension.add(extProject.getName());
                    projects.add(entry(extProject.getName(), "extension", "SKIPPED", null,
                        extName == null ? reason : "расширения нет в базе"));
                    continue;
                }
                covered.add(upper(extName));
                try {
                    ProjectFromInfobaseUpdater.Outcome extOutcome = InfobaseJobs.updateProject(ctx, updater,
                        extProject, infobase, discard, new AfterLoad(dtLoaded, List.of()));
                    projects.add(updated(extProject.getName(), "extension", extOutcome));
                } catch (Exception e) {
                    if (ctx.monitor().isCanceled()) throw e;
                    projects.add(entry(extProject.getName(), "extension", "FAILED", null, McpJobs.describe(e)));
                }
            }
            List<String> withoutProject = new ArrayList<>();
            for (String name : inInfobase) {
                if (!covered.contains(upper(name))) withoutProject.add(name);
            }
            ctx.put("extensionsWithoutProject", withoutProject);
            ctx.put("projectsWithoutExtension", projectsWithoutExtension);
            ctx.put("extensionProjectsOfOtherInfobases", ofOtherInfobases);
        }
        ctx.put("projects", projects);
    }

    private static Path backupPath(String value) throws ToolException {
        if (value == null) return null;
        Path path;
        try {
            path = Paths.get(value);
        } catch (InvalidPathException e) {
            throw new ToolException("'backupTo': некорректный путь '" + value + "'");
        }
        if (!path.isAbsolute()) throw new ToolException("'backupTo': нужен абсолютный путь, получен '" + value + "'");
        String fileName = path.getFileName() == null ? value : path.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".dt")) {
            throw new ToolException("'backupTo': ожидается файл .dt, получен " + fileName);
        }
        if (Files.exists(path)) {
            throw new ToolException("'backupTo': файл уже существует, перезаписывать выгрузку не буду: " + value);
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ToolException("'backupTo': каталог не существует: " + parent);
        }
        return path;
    }

    /** Обновлённый проект: предупреждение итога — статус WARNING с текстом в message. */
    private static Map<String, Object> updated(String project, String kind, ProjectFromInfobaseUpdater.Outcome outcome) {
        return entry(project, kind, outcome.warning() == null ? "OK" : "WARNING", outcome.resolution(),
            outcome.warning());
    }

    private static Map<String, Object> entry(String project, String kind, String status, String resolution,
                                             String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("project", project);
        e.put("kind", kind);
        e.put("status", status);
        e.put("resolution", resolution);
        e.put("message", message);
        return e;
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
