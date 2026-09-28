package ru.fedukhin.edt.mcp.tools.infobase;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SchemaBuilder;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ToolArgs;

/**
 * «Проект ← база»: конфигурация связанной базы забирается в проект, содержимое проекта
 * заменяется версией из базы. Раньше это делалось только руками в IDE. Проект расширения без своей
 * связи с базой берёт базу родительской конфигурации ({@link InfobaseTargets#resolve}).
 */
public class UpdateProjectFromInfobaseTool implements IMcpTool {

    static final int DEFAULT_TIMEOUT_MINUTES = 180;

    private final InfobaseTargets targets;
    private final SyncV2 syncV2;
    private final ProjectFromInfobaseUpdater updater;
    private final ThickClientOps ops;
    private final InfobaseJobs jobs;

    @Inject
    public UpdateProjectFromInfobaseTool(InfobaseTargets targets, SyncV2 syncV2, ProjectFromInfobaseUpdater updater,
                                         ThickClientOps ops, InfobaseJobs jobs) {
        this.targets = targets;
        this.syncV2 = syncV2;
        this.updater = updater;
        this.ops = ops;
        this.jobs = jobs;
    }

    @Override public String name() { return "update_project_from_infobase"; }

    @Override public String description() {
        return "Pull the configuration of the associated infobase INTO the project: project content is "
            + "replaced with the infobase version. Configuration or extension projects only. Before the pull the "
            + "tool closes EDT's designer agent session on the infobase and resets EDT's quick «infobase "
            + "unchanged» check, so that EDT compares the infobase with the project even if the infobase was "
            + "changed outside EDT (.dt or .cfe load, designer); if either step fails, a «no changes» result "
            + "carries a warning with the reason. Refuses while the "
            + "project has changes not deployed to the infobase, or EDT has no synchronization state for it "
            + "(e.g. a new project), unless discardProjectChanges=true. discardProjectChanges=true is a full "
            + "replacement: the whole configuration is exported from the infobase, and whatever the infobase "
            + "does not have is deleted from the project; if EDT still answers «no changes», the result says the "
            + "project was not replaced. Runs as a background job: returns jobId, poll get_job_status. Requires "
            + "1C:EDT 2026.1+.";
    }

    @Override public Map<String, Object> inputSchema() {
        return SchemaBuilder.object()
            .string("project", "Project to update: configuration or extension", true)
            .string("infobase", "Infobase name; default is the project's default associated infobase (an extension "
                + "project without its own association uses its parent configuration's infobase)", false)
            .string("user", "Infobase user for the designer; saved to EDT access settings of the infobase", false)
            .string("password", "Password of the infobase user", false)
            .bool("discardProjectChanges", "Full replacement: export the whole configuration from the infobase and "
                + "delete from the project whatever the infobase does not have, even if the project has changes not "
                + "deployed to the infobase or no synchronization state yet (a new project)")
            .bool("allowForeignInfobase", "Allow an infobase the project is not associated with")
            .integer("timeoutMinutes", "Job time limit, minutes (default 180)", 1, 1440)
            .build();
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = ToolArgs.required(args, "project");
        IProject project = targets.openProject(projectName);
        // R7-3 (fix round 7): только конфигурация или расширение — состояние прочих зависимых проектов EDT хранит у
        // родительской конфигурации, и «обновление» задело бы родителя.
        targets.configurationOrExtensionProject(project);
        syncV2.requireAvailable();
        InfobaseTargets.Target target = targets.resolve(project, ToolArgs.optional(args, "infobase"),
            ToolArgs.flag(args, "allowForeignInfobase", false));
        InfobaseReference infobase = target.infobase();
        String user = ToolArgs.optional(args, "user");
        String password = ToolArgs.optional(args, "password");
        boolean discard = ToolArgs.flag(args, "discardProjectChanges", false);
        if (!discard) {
            // Быстрый отказ в очевидных случаях; окончательная проверка — в задании, после ожидания модели EDT
            // (ProjectFromInfobaseUpdater.update с discardProjectChanges = false).
            List<String> atRisk = syncV2.projectsAtRisk(List.of(project), infobase);
            if (!atRisk.isEmpty()) throw new ToolException(SyncV2.atRiskMessage(atRisk));
        }
        Duration timeout = Duration.ofMinutes(
            ToolArgs.integer(args, "timeoutMinutes", DEFAULT_TIMEOUT_MINUTES, 1, 1440));

        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("project", projectName);
        echo.put("infobase", infobase.getName());
        if (target.warning() != null) echo.put("warning", target.warning());

        return jobs.start(name(), "проект " + projectName + " из базы " + infobase.getName(), infobase,
            projectName, timeout, ctx -> {
                ctx.put("project", projectName);
                ctx.put("infobase", infobase.getName());
                if (user != null) {
                    ctx.step("access-settings", () -> {
                        ops.storeCredentials(infobase, user, password);
                        return null;
                    });
                }
                ProjectFromInfobaseUpdater.Outcome outcome =
                    InfobaseJobs.updateProject(ctx, updater, project, infobase, discard);
                ctx.put("resolution", outcome.resolution());
                ctx.put("durationMs", outcome.durationMs());
                if (outcome.warning() != null) ctx.put("warning", outcome.warning());
            }, echo);
    }
}
