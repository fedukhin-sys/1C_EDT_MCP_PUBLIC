package ru.fedukhin.edt.mcp.tools.infobase;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * Сценарий 2: файлы {@code .cfe} загружаются в связанную с проектом базу (расширение создаётся или
 * заменяется и применяется), затем проекты расширений обновляются из базы. Расширения
 * обрабатываются независимо: сбой одного не останавливает остальные.
 *
 * <p>Проекты расширений с базой НЕ связываются: EDT 2026.1 связывает базу только с одним проектом — с
 * конфигурацией («Infobase X is already associated with project P»), а проект расширения без своей
 * связи следует за ней. Проект расширения, связанный только с другой базой, из этой базы не
 * обновляется — шаг {@code WARNING} с причиной.
 */
public class UpdateExtensionsFromCfeTool implements IMcpTool {

    static final int DEFAULT_TIMEOUT_MINUTES = 120;

    private record Item(Path file, String name) {}

    /** Проект расширения, связанный только с другими базами, и причина, по которой он не обновляется. */
    private record Skipped(String project, String reason) {}

    private final InfobaseTargets targets;
    private final SyncV2 syncV2;
    private final ProjectFromInfobaseUpdater updater;
    private final ThickClientOps ops;
    private final InfobaseJobs jobs;

    @Inject
    public UpdateExtensionsFromCfeTool(InfobaseTargets targets, SyncV2 syncV2, ProjectFromInfobaseUpdater updater,
                                       ThickClientOps ops, InfobaseJobs jobs) {
        this.targets = targets;
        this.syncV2 = syncV2;
        this.updater = updater;
        this.ops = ops;
        this.jobs = jobs;
    }

    @Override public String name() { return "update_extensions_from_cfe"; }

    @Override public String description() {
        return "Load .cfe files into the infobase associated with the configuration project (the extension is "
            + "created or replaced, then applied to the database), then pull each extension into its extension "
            + "project in the workspace (project content is replaced with the infobase version). Extension name "
            + "defaults to the file name without .cfe and should equal the extension's own name inside the .cfe. "
            + "Extensions are processed independently. Extension projects "
            + "follow the parent configuration's infobase and are never associated with it; one associated only "
            + "with another infobase is not updated (WARNING). Refuses while the extension projects have changes "
            + "not deployed to the infobase, or EDT has no synchronization state for them (e.g. a new project), "
            + "unless discardProjectChanges=true. Background job: returns jobId, poll get_job_status. Updating "
            + "projects requires 1C:EDT 2026.1+.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> item = SchemaBuilder.object()
            .string("file", "Absolute path to the .cfe file", true)
            .string("name", "Name under which the extension is loaded into the infobase; default is the file name "
                + "without .cfe. Should equal the extension's own name inside the .cfe (its Name property): loaded "
                + "under another name, applying can fail with 'Расширение с таким именем уже существует!', and the "
                + "loaded but unapplied extension then stays in the infobase configuration", false)
            .build();
        return SchemaBuilder.object()
            .string("project", "Configuration project whose infobase receives the extensions", true)
            .array("extensions", "Extensions to load: [{file, name?}]", item, true)
            .string("infobase", "Infobase name; default is the project's default associated infobase", false)
            .bool("updateProjects", "Update extension projects from the infobase afterwards (default true)")
            .string("user", "Infobase user for the designer; saved to EDT access settings of the infobase", false)
            .string("password", "Password of the infobase user", false)
            .bool("discardProjectChanges", "Full replacement of every updated extension project: the whole extension "
                + "is exported from the infobase, whatever it does not have is deleted from the project, even if the "
                + "projects have changes not deployed to the infobase or no synchronization state yet (a new project)")
            .bool("allowForeignInfobase", "Allow an infobase the project is not associated with")
            .integer("timeoutMinutes", "Job time limit, minutes (default 120)", 1, 1440)
            .build();
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = ToolArgs.required(args, "project");
        IProject project = targets.openProject(projectName);
        targets.configurationProject(project);
        List<Item> items = parseItems(args.get("extensions"));
        boolean updateProjects = ToolArgs.flag(args, "updateProjects", true);
        if (updateProjects) syncV2.requireAvailable();
        InfobaseTargets.Target target = targets.resolve(project, ToolArgs.optional(args, "infobase"),
            ToolArgs.flag(args, "allowForeignInfobase", false));
        InfobaseReference infobase = target.infobase();

        Map<String, IExtensionProject> projectsByName = new HashMap<>();
        Map<String, Skipped> skippedByName = new HashMap<>();
        List<IProject> overwritten = new ArrayList<>();
        if (updateProjects) {
            for (Item item : items) {
                Optional<IExtensionProject> found = targets.extensionProject(project, item.name());
                if (found.isEmpty()) continue;
                IProject extProject = found.get().getProject();
                if (targets.belongsTo(extProject, infobase)) {
                    projectsByName.put(upper(item.name()), found.get());
                    overwritten.add(extProject);
                } else {
                    skippedByName.put(upper(item.name()), new Skipped(extProject.getName(),
                        otherInfobaseReason(extProject.getName(), targets.associatedInfobases(extProject),
                            infobase.getName())));
                }
            }
        }
        boolean discard = ToolArgs.flag(args, "discardProjectChanges", false);
        boolean check = updateProjects && !overwritten.isEmpty() && !discard;
        // I2 (fix round 9): отказ ДО загрузки — свой текст, без совета deploy_project: загрузка .cfe заменила бы
        // залитое в расширение, а затем и проект — версией из базы. Выход — сохранить правки (git) и повторить с флагом.
        Function<List<String>, String> refusal = atRisk -> SyncV2.atRiskBeforeLoadMessage("загрузка .cfe заменит в "
            + "базе " + infobase.getName() + " расширения " + String.join(", ", items.stream()
                .map(item -> "«" + item.name() + "»").toList()), atRisk, false);
        if (check) {
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
        echo.put("extensions", items.stream().map(Item::name).toList());
        if (target.warning() != null) echo.put("warning", target.warning());

        List<IProject> toCheck = check ? overwritten : List.of();
        return jobs.start(name(), "расширения базы " + infobase.getName() + " из .cfe", infobase, projectName,
            timeout, ctx -> run(ctx, project, infobase, items, projectsByName, skippedByName, updateProjects, user,
                password, discard, toCheck, refusal), echo);
    }

    /**
     * @param discard {@code discardProjectChanges} вызова — доходит до обновления каждого проекта расширения как
     *     есть (fix round 6, F4): идентификатор поколения расширения после {@code /LoadCfg -Extension} неизвестен, и
     *     без сброса быстрой проверки EDT могла бы ответить «изменений нет». {@code false} — проверка проекта после
     *     ожидания модели (отказ — со своим текстом: расширение уже загружено, {@code deploy_project} сейчас не
     *     вызывать, fix round 6b), затем закрытие сеанса агента и сброс быстрой проверки — сравнение с записанным
     *     состоянием, если оба шага удались (не удались — «изменений нет» с предупреждением; fix round 7);
     *     {@code true} — полная замена
     * @param toCheck проекты расширений, которые задание проверяет заново до первой загрузки {@code .cfe} (шаг
     *     {@code check-projects}); пусто — {@code discardProjectChanges}, {@code updateProjects = false} или
     *     проектов нет
     * @param refusal текст отказа {@code check-projects} по его пунктам — тот же, что при вызове (I2)
     */
    private void run(JobContext ctx, IProject project, InfobaseReference infobase, List<Item> items,
                     Map<String, IExtensionProject> projectsByName, Map<String, Skipped> skippedByName,
                     boolean updateProjects, String user, String password, boolean discard, List<IProject> toCheck,
                     Function<List<String>, String> refusal) throws Exception {
        ctx.put("infobase", infobase.getName());
        if (!toCheck.isEmpty()) InfobaseJobs.checkProjects(ctx, updater, toCheck, infobase, refusal);
        if (user != null) {
            ctx.step("access-settings", () -> {
                ops.storeCredentials(infobase, user, password);
                return null;
            });
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Item item : items) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", item.name());
            r.put("file", item.file().toString());
            IExtensionProject ext = projectsByName.get(upper(item.name()));
            Skipped skipped = skippedByName.get(upper(item.name()));
            r.put("project", ext != null ? ext.getProject().getName() : skipped != null ? skipped.project() : null);
            try {
                ctx.step("load-cfe " + item.name(), () -> {
                    ops.loadExtension(project, infobase, item.file(), item.name(), ctx.monitor());
                    return null;
                });
                r.put("loaded", true);
                ctx.step("apply " + item.name(), () -> {
                    try {
                        ops.applyExtension(project, infobase, item.name(), ctx.monitor());
                    } catch (ToolException e) {
                        // X9 (fix round 9, живой прогон): .cfe загружен под чужим именем — применение падает, а
                        // загруженное расширение остаётся в конфигурации базы; подсказка — что с этим делать.
                        if (!isNameClash(e.getMessage())) throw e;
                        throw new ToolException(e.getMessage() + " — " + nameClashHint(item.name()), e);
                    }
                    return null;
                });
                r.put("applied", true);
                if (!updateProjects) {
                    r.put("status", "OK");
                } else if (skipped != null) {
                    ctx.record("update-project " + skipped.project(), StepStatus.WARNING, skipped.reason());
                    r.put("status", "WARNING");
                    r.put("message", skipped.reason());
                } else if (ext == null) {
                    String message = missingProjectHint(item.name(), project.getName());
                    ctx.record("update-project " + item.name(), StepStatus.WARNING, message);
                    r.put("status", "WARNING");
                    r.put("message", message);
                } else {
                    // Без associate: проект расширения своей связи не имеет и следует за родительской
                    // конфигурацией, а связать его с базой EDT не даст — база уже связана с конфигурацией.
                    // m2 (fix round 9): всё, чем кончится обновление проекта после загрузки .cfe, — отказ, сбой,
                    // предупреждение — несёт указание «после загрузки»: deploy_project сейчас не вызывать.
                    String loaded = "расширение «" + item.name() + "» (файл «" + item.file().getFileName()
                        + "») уже загружено в базу " + infobase.getName() + " и применено";
                    ProjectFromInfobaseUpdater.Outcome outcome = InfobaseJobs.updateProject(ctx, updater,
                        ext.getProject(), infobase, discard, new AfterLoad(loaded, List.of()));
                    r.put("projectUpdated", true);
                    r.put("resolution", outcome.resolution());
                    if (outcome.warning() == null) {
                        r.put("status", "OK");
                    } else {
                        r.put("status", "WARNING");
                        r.put("message", outcome.warning());
                    }
                }
            } catch (Exception e) {
                if (ctx.monitor().isCanceled()) throw e;
                r.put("status", "FAILED");
                r.put("message", McpJobs.describe(e));
            }
            results.add(r);
        }
        ctx.put("extensions", results);
    }

    /**
     * Подсказка, когда проекта расширения нет. Пустой проект, созданный по ней, с базой ещё ни разу
     * не синхронизировался — {@code update_project_from_infobase} без {@code discardProjectChanges: true}
     * откажет, а {@code deploy_project} залил бы пустой проект В базу. Поэтому путь — только
     * «проект ← база» с явным флагом. {@code associate_infobase} здесь не поможет: база уже связана с
     * проектом конфигурации, а проект расширения берёт её у родителя.
     */
    private static String missingProjectHint(String extensionName, String configurationProject) {
        return "проекта расширения '" + extensionName + "' нет в рабочей области: расширение в базу уже "
            + "загружено, создайте пустой проект расширения с этим именем (create_project type=extension, "
            + "parentConfigurationName=" + configurationProject + ") и выполните update_project_from_infobase "
            + "для него с discardProjectChanges: true — база берётся у родительской конфигурации, а пустой проект "
            + "с ней ещё не синхронизирован";
    }

    /** Текст платформы при применении расширения, загруженного под именем, отличным от собственного (X9). */
    static final String NAME_CLASH = "Расширение с таким именем уже существует";

    private static boolean isNameClash(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains(NAME_CLASH.toLowerCase(Locale.ROOT));
    }

    /**
     * Подсказка к X9 (fix round 9, живой прогон): {@code .cfe} с собственным именем расширения {@code SmokeNew2Ext},
     * загруженный под именем {@code SmokeCopy} (по умолчанию — имя файла), не применился — «Расширение с таким
     * именем уже существует!», а загруженное, но не применённое расширение осталось в конфигурации базы.
     */
    private static String nameClashHint(String name) {
        return "похоже, собственное имя расширения внутри .cfe отличается от имени '" + name + "', под которым его "
            + "загрузили (параметр name; по умолчанию — имя файла без .cfe): передайте в name собственное имя "
            + "расширения. Загруженное, но не применённое расширение '" + name + "' осталось в конфигурации базы — "
            + "удалите его в конфигураторе или загрузите .cfe под собственным именем расширения";
    }

    /** Причина пропуска проекта расширения, связанного только с другими базами. */
    private static String otherInfobaseReason(String extensionProject, List<String> itsInfobases, String infobase) {
        return "проект расширения '" + extensionProject + "' связан с другой базой " + itsInfobases
            + " — из базы '" + infobase + "' он не обновлялся (расширение в неё загружено и применено); обновите "
            + "его из его базы (update_project_from_infobase) или загрузите .cfe туда";
    }

    private static List<Item> parseItems(Object raw) throws ToolException {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new ToolException("'extensions' must be a non-empty array of {file, name?}");
        }
        List<Item> items = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                throw new ToolException("each 'extensions' item must be an object {file, name?}");
            }
            Object fileArg = map.get("file");
            if (!(fileArg instanceof String fileText) || fileText.isBlank()) {
                throw new ToolException("'extensions[].file' is required");
            }
            Path file = ToolArgs.existingFile("extensions[].file", fileText, "cfe");
            Object nameArg = map.get("name");
            String name;
            if (nameArg == null) {
                name = baseName(file);
            } else if (nameArg instanceof String text) {
                name = text.isBlank() ? baseName(file) : text.strip();
            } else {
                throw new ToolException("'extensions[].name' must be a string");
            }
            if (!names.add(upper(name))) {
                throw new ToolException("расширение '" + name + "' указано дважды");
            }
            items.add(new Item(file, name));
        }
        return items;
    }

    private static String baseName(Path cfe) {
        String fileName = cfe.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".cfe".length());
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
