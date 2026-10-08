package ru.fedukhin.edt.mcp.tools.infobase;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtApplicationState;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncSnapshot;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectSyncState;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SchemaBuilder;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncStateProbe;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncStateReport;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ToolArgs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.UpdateStateRule;

/**
 * Диагностика окна EDT «Конфигурация информационной базы не синхронизирована с проектом…» при запуске клиента: по
 * проекту конфигурации и каждому его проекту расширения — состояние синхронизации с базой глазами EDT и итог, который
 * решает, будет ли окно. Только чтение. Механика и решения — спека
 * {@code docs/superpowers/specs/2026-10-08-mcp-get-infobase-sync-state-design.md}.
 */
public class GetInfobaseSyncStateTool implements IMcpTool {

    static final int DEFAULT_MAX_FILES = 50;
    static final int MAX_FILES_LIMIT = 5000;

    private final InfobaseTargets targets;
    private final InfobaseRegistry registry;
    private final IV8ProjectManager projects;
    private final SyncStateProbe probe;

    @Inject
    public GetInfobaseSyncStateTool(InfobaseTargets targets, InfobaseRegistry registry, IV8ProjectManager projects,
                                    SyncStateProbe probe) {
        this.targets = targets;
        this.registry = registry;
        this.projects = projects;
        this.probe = probe;
    }

    @Override public String name() { return "get_infobase_sync_state"; }

    @Override public String description() {
        return "Diagnose why 1C:EDT shows the dialog «Обновление приложения: Конфигурация информационной базы … не "
            + "синхронизирована с проектом …, требуется загрузка изменённых объектов» when launching the client. "
            + "Read-only: writes nothing to disk or to EDT settings; EDT's synchronization-state monitor is held only "
            + "while the snapshot is copied. For the configuration project and EVERY extension project depending on "
            + "it: equalityState (IInfobaseSynchronizationManager.getEqualityState: EQUAL / NOT_EQUAL / LOADING), "
            + "connected (isConnected), projectDirty (EDT 2026.1+) and the project's updateState. "
            + "applicationUpdateState is what EDT itself answers at client launch (IApplicationManager.getUpdateState of "
            + "the infobase application): UPDATED — no dialog; INCREMENTAL_UPDATE_REQUIRED, FULL_UPDATE_REQUIRED, "
            + "UNKNOWN — the dialog; BEING_UPDATED — the launch is cancelled. computedUpdateState is the same EDT rule "
            + "recomputed from the per-project values; launchDialogExpected and summary name the projects at fault. "
            + "An infobase not associated with the project has no EDT application: applicationUpdateState and "
            + "launchDialogExpected are null. "
            + "details=true adds per project the differing files: project resource signatures (SHA-256) vs EDT's "
            + "synchronization snapshot in memory (what EDT compares with now) and on disk (index.idx, loaded after an "
            + "EDT restart). An extension project argument means its parent configuration. Values this EDT version "
            + "cannot provide are null with the reason.";
    }

    @Override public Map<String, Object> inputSchema() {
        return SchemaBuilder.object()
            .string("project", "Configuration project, or an extension project (its parent configuration is checked)",
                true)
            .string("infobase", "Infobase name; default is the configuration project's default associated infobase",
                false)
            .bool("details", "List differing files per project: resource signatures vs EDT's synchronization "
                + "snapshot in memory and on disk")
            .integer("maxFiles", "details: at most this many paths per list per project (default 50)", 1,
                MAX_FILES_LIMIT)
            .build();
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = ToolArgs.required(args, "project");
        String infobaseName = ToolArgs.optional(args, "infobase");
        boolean details = ToolArgs.flag(args, "details", false);
        int maxFiles = ToolArgs.integer(args, "maxFiles", DEFAULT_MAX_FILES, 1, MAX_FILES_LIMIT);

        IProject requested = targets.openProject(projectName);
        IProject configuration = configurationOf(requested);
        String warning = null;
        boolean foreign = false;
        InfobaseReference infobase;
        if (infobaseName != null) {
            infobase = registry.findByName(infobaseName)
                .orElseThrow(() -> new ToolException("infobase '" + infobaseName + "' not found"));
            try {
                foreign = !targets.associatedInfobases(configuration).contains(infobaseName);
            } catch (ToolException e) {
                warning = "не удалось проверить, связана ли база '" + infobaseName + "' с проектом '"
                    + configuration.getName() + "': " + e.getMessage();
            }
            if (foreign) {
                warning = "проект '" + configuration.getName() + "' не связан с базой '" + infobaseName
                    + "': приложения EDT для этой пары нет, и запуск клиента её не проверяет — показаны только "
                    + "состояния синхронизации проектов с этой базой";
            }
        } else {
            infobase = targets.resolve(configuration, null, false).infobase();
        }

        List<IProject> extensions;
        try {
            extensions = probe.extensionProjects(configuration);
        } catch (RuntimeException e) {
            throw new ToolException("EDT не отдала проекты расширений конфигурации '" + configuration.getName()
                + "': " + McpJobs.describe(e), e);
        }
        ProjectSyncState main = probe.read(configuration, ProjectSyncState.CONFIGURATION, infobase);
        List<ProjectSyncState> extensionStates = new ArrayList<>();
        for (IProject extension : extensions) {
            extensionStates.add(probe.read(extension, ProjectSyncState.EXTENSION, infobase));
        }
        String computed = UpdateStateRule.overall(main, extensionStates);
        EdtApplicationState.Result application = foreign
            ? new EdtApplicationState.Result(null, "база не связана с проектом — приложения EDT для этой пары нет")
            : probe.applicationState(configuration, infobase);
        String decisive = application.state() != null ? application.state() : computed;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", configuration.getName());
        if (!configuration.equals(requested)) out.put("requestedProject", requested.getName());
        out.put("infobase", infobase.getName());
        out.put("infobaseUuid", infobase.getUuid() == null ? null : infobase.getUuid().toString());
        if (warning != null) out.put("warning", warning);
        out.put("applicationUpdateState", application.state());
        if (application.error() != null) out.put("applicationUpdateStateError", application.error());
        out.put("computedUpdateState", computed);
        out.put("launchDialogExpected", foreign ? null : UpdateStateRule.dialogExpected(decisive));
        out.put("summary", foreign ? foreignSummary(configuration, infobase, computed, main, extensionStates)
            : summary(decisive, application.state(), computed, infobase, main, extensionStates, details));

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(SyncStateReport.row(main));
        for (ProjectSyncState state : extensionStates) rows.add(SyncStateReport.row(state));
        if (details) {
            List<IProject> all = new ArrayList<>();
            all.add(configuration);
            all.addAll(extensions);
            EdtSyncSnapshot.Read snapshot = probe.snapshot(configuration, infobase,
                extensions.stream().map(IProject::getName).toList());
            for (int i = 0; i < all.size(); i++) {
                SyncStateProbe.Current current = probe.currentSignatures(all.get(i));
                rows.get(i).put("details", SyncStateReport.details(all.get(i).getName(), current.files(),
                    current.failure(), snapshot, maxFiles));
            }
        }
        out.put("projects", rows);
        return out;
    }

    private static String summary(String decisive, String fromEdt, String computed, InfobaseReference infobase,
                                  ProjectSyncState main, List<ProjectSyncState> extensions, boolean details) {
        StringBuilder text = new StringBuilder(UpdateStateRule.summary(decisive, infobase.getName(), main,
            extensions));
        if (fromEdt != null && computed != null && !fromEdt.equals(computed)) {
            text.append(". Ответ EDT (").append(fromEdt).append(") и расчёт по проектам (").append(computed)
                .append(") расходятся: состояние менялось во время вызова или в этой версии EDT правило другое");
        }
        if (!details && (Boolean.TRUE.equals(main.projectDirty())
                || extensions.stream().anyMatch(e -> Boolean.TRUE.equals(e.projectDirty())))) {
            text.append(". Какие файлы расходятся — details=true");
        }
        return text.toString();
    }

    /** Сводка для базы, не связанной с проектом: окна при запуске для неё не бывает, правило — только справочно. */
    private static String foreignSummary(IProject configuration, InfobaseReference infobase, String computed,
                                         ProjectSyncState main, List<ProjectSyncState> extensions) {
        List<String> problems = UpdateStateRule.problems(main, extensions);
        return "База '" + infobase.getName() + "' не связана с проектом '" + configuration.getName() + "': приложения "
            + "EDT для этой пары нет, окна при запуске для неё не бывает. По правилу запуска EDT итог был бы "
            + (computed == null ? "не определён" : computed) + (problems.isEmpty() ? "" : ": " + String.join("; ",
            problems));
    }

    /**
     * Проект конфигурации, чьё приложение проверяет запуск клиента: сам проект или родитель проекта расширения.
     * У прочих проектов (внешние отчёты и обработки) своего состояния синхронизации с базой нет — отказ.
     */
    private IProject configurationOf(IProject requested) throws ToolException {
        IV8Project v8 = projects.getProject(requested);
        if (v8 instanceof IConfigurationProject) return requested;
        if (v8 instanceof IExtensionProject extension) {
            IProject parent = extension.getParentProject();
            if (parent == null || !parent.exists() || !parent.isOpen()) {
                throw new ToolException("родительская конфигурация проекта расширения '" + requested.getName()
                    + "' не открыта в workspace" + (parent == null ? "" : ": '" + parent.getName() + "'"));
            }
            return parent;
        }
        String kind = v8 == null ? "EDT ещё не загрузила проект или это не проект 1С"
            : v8 instanceof IExternalObjectProject ? "это проект внешних отчётов и обработок"
            : "тип " + v8.getClass().getSimpleName();
        throw new ToolException("проект '" + requested.getName() + "' — не проект конфигурации и не проект "
            + "расширения (" + kind + "): состояние синхронизации с базой есть только у них");
    }
}
