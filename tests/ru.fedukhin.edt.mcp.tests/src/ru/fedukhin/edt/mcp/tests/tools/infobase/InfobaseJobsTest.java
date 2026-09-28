package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.ipc.InterProcessLock;
import ru.fedukhin.edt.mcp.core.jobs.JobLauncher;
import ru.fedukhin.edt.mcp.core.jobs.JobStatus;
import ru.fedukhin.edt.mcp.core.jobs.JobStep;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.core.jobs.StepStatus;
import ru.fedukhin.edt.mcp.tools.infobase.internal.AfterLoad;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncStateJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;

public class InfobaseJobsTest {

    private final McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
    private final InfobaseJobs infobaseJobs = new InfobaseJobs(() -> jobs, Duration.ofMillis(300));

    private static String key() {
        return "ib:test:" + UUID.randomUUID();
    }

    @Test
    public void start_answersWithJobIdEchoAndPoll_andReleasesLockAfterJob() throws Exception {
        String key = key();
        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("project", "Demo");
        echo.put("infobase", "DemoIB");

        Map<String, Object> out = infobaseJobs.start("restore_infobase_from_dt", "проба", key, "Demo", null,
            ctx -> ctx.put("done", true), echo);

        String id = (String) out.get("jobId");
        assertEquals(JobStatus.SUCCEEDED, jobs.find(id).orElseThrow().status());
        assertEquals("restore_infobase_from_dt", out.get("tool"));
        assertEquals("Demo", out.get("project"));
        assertEquals("DemoIB", out.get("infobase"));
        assertEquals("get_job_status", out.get("poll"));
        try (InterProcessLock again = InterProcessLock.acquire(key, "test", Duration.ofMillis(300))) {
            assertNotNull("замок снимает само задание", again);
        }
    }

    @Test
    public void failedJob_releasesLockToo() throws Exception {
        String key = key();

        Map<String, Object> out = infobaseJobs.start("restore_infobase_from_dt", "проба", key, "Demo", null,
            ctx -> { throw new IllegalStateException("сбой"); }, Map.of());

        assertEquals(JobStatus.FAILED, jobs.find((String) out.get("jobId")).orElseThrow().status());
        try (InterProcessLock again = InterProcessLock.acquire(key, "test", Duration.ofMillis(300))) {
            assertNotNull(again);
        }
    }

    /**
     * Fix round 8 (L9): первый шаг каждого задания — дождаться фоновых проверок EDT «Обновление состояния
     * синхронизации проекта» (после запуска EDT или открытия проекта), до всего, что трогает агент или состояние.
     */
    @Test
    public void start_firstStepWaitsForEdtChecks() throws Exception {
        Map<String, Object> out = infobaseJobs.start("update_project_from_infobase", "проба", key(), "Demo", null,
            ctx -> ctx.step("update-project Demo", () -> null), Map.of());

        McpJob job = jobs.find((String) out.get("jobId")).orElseThrow();
        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        assertEquals(InfobaseJobs.EDT_CHECKS_STEP, job.steps().get(0).name());
        assertEquals(StepStatus.OK, job.steps().get(0).status());
        assertEquals("update-project Demo", job.steps().get(1).name());
    }

    /** Проверки EDT не кончились за предел — задание падает на первом шаге, тело не выполняется. */
    @Test
    public void edtChecksLongerThanBound_failsFirstStep_bodyNotRun() throws Exception {
        EdtSyncStateJobs edtJobs = mock(EdtSyncStateJobs.class);
        when(edtJobs.await(any())).thenReturn("EDT ещё проверяет состояние синхронизации проектов (проба)");
        InfobaseJobs waiting = new InfobaseJobs(() -> jobs, Duration.ofMillis(300), edtJobs);

        Map<String, Object> out = waiting.start("restore_infobase_from_dt", "проба", key(), "Demo", null,
            ctx -> ctx.put("bodyRan", true), Map.of());

        McpJob job = jobs.find((String) out.get("jobId")).orElseThrow();
        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("EDT ещё проверяет состояние синхронизации проектов"));
        assertTrue(job.error(), job.error().contains("повторите позже; ничего не тронуто"));
        assertEquals(1, job.steps().size());
        assertEquals(InfobaseJobs.EDT_CHECKS_STEP, job.steps().get(0).name());
        assertEquals(StepStatus.FAILED, job.steps().get(0).status());
        assertEquals(null, job.result().get("bodyRan"));
    }

    @Test
    public void busyInfobase_refusesImmediately_namingHolder() throws Exception {
        String key = key();
        try (InterProcessLock held = InterProcessLock.acquire(key, "deploy_project project=Other", Duration.ofMillis(300))) {
            try {
                infobaseJobs.start("restore_infobase_from_dt", "проба", key, "Demo", null, ctx -> { }, Map.of());
                fail("expected ToolException");
            } catch (ToolException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("deploy_project project=Other"));
            }
        }
        assertTrue("задание не должно было запуститься", jobs.list().isEmpty());
    }

    @Test
    public void updateProject_warningOfOutcome_isRecordedAsWarningStep() throws Exception {
        List<JobStep> steps = updateProjectSteps(
            new ProjectFromInfobaseUpdater.Outcome("NO_CHANGES", 3L, "правки проекта Demo остались"));

        assertEquals(3, steps.size());
        assertEquals(InfobaseJobs.EDT_CHECKS_STEP, steps.get(0).name());
        assertEquals("update-project Demo", steps.get(1).name());
        assertEquals(StepStatus.OK, steps.get(1).status());
        assertEquals("verify-project Demo", steps.get(2).name());
        assertEquals(StepStatus.WARNING, steps.get(2).status());
        assertEquals("правки проекта Demo остались", steps.get(2).message());
    }

    @Test
    public void updateProject_withoutWarning_isSingleStep() throws Exception {
        List<JobStep> steps = updateProjectSteps(new ProjectFromInfobaseUpdater.Outcome("CHANGES_RESOLVED", 3L));

        assertEquals(2, steps.size());
        assertEquals(StepStatus.OK, steps.get(1).status());
    }

    /**
     * O1 (fix round 5): шаг {@code check-projects} — проекты, которые сейчас будут перезаписаны, проверяются
     * после ожидания модели EDT; есть риск — шаг и задание падают с текстом отказа, дальше дело не идёт.
     */
    @Test
    public void checkProjects_atRisk_failsJobBeforeNextStep() throws Exception {
        ProjectFromInfobaseUpdater updater = mock(ProjectFromInfobaseUpdater.class);
        IProject project = mock(IProject.class);
        InfobaseReference ib = mock(InfobaseReference.class);
        when(updater.projectsAtRiskAfterModelSync(List.of(project), ib))
            .thenReturn(List.of("Demo (есть правки, не залитые в базу)"));

        Map<String, Object> out = infobaseJobs.start("restore_infobase_from_dt", "проба", key(), "Demo", null, ctx -> {
            InfobaseJobs.checkProjects(ctx, updater, List.of(project), ib, SyncV2::atRiskMessage);
            ctx.put("destructiveStepReached", true);
        }, Map.of());

        McpJob job = jobs.find((String) out.get("jobId")).orElseThrow();
        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("Demo (есть правки, не залитые в базу)"));
        assertTrue(job.error(), job.error().contains("discardProjectChanges"));
        assertEquals("check-projects", job.steps().get(1).name());
        assertEquals(StepStatus.FAILED, job.steps().get(1).status());
        assertEquals(null, job.result().get("destructiveStepReached"));
    }

    @Test
    public void checkProjects_clean_isOkStep() throws Exception {
        ProjectFromInfobaseUpdater updater = mock(ProjectFromInfobaseUpdater.class);
        IProject project = mock(IProject.class);
        InfobaseReference ib = mock(InfobaseReference.class);
        when(updater.projectsAtRiskAfterModelSync(List.of(project), ib)).thenReturn(List.of());

        Map<String, Object> out = infobaseJobs.start("restore_infobase_from_dt", "проба", key(), "Demo", null,
            ctx -> InfobaseJobs.checkProjects(ctx, updater, List.of(project), ib, SyncV2::atRiskMessage), Map.of());

        McpJob job = jobs.find((String) out.get("jobId")).orElseThrow();
        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        assertEquals("check-projects", job.steps().get(1).name());
        assertEquals(StepStatus.OK, job.steps().get(1).status());
    }

    /**
     * Fix round 9 (m2): отмена (или лимит времени) обновления проекта после загрузки остаётся отменой — тип
     * {@link OperationCanceledException}, реестр доведёт задание до CANCELLED, — но и её текст несёт указание «после
     * загрузки»: загрузка сделана, deploy_project сейчас не вызывать.
     */
    @Test
    public void updateProject_afterLoad_cancellationStaysCancellation_withGuidance() throws Exception {
        ProjectFromInfobaseUpdater updater = mock(ProjectFromInfobaseUpdater.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");
        InfobaseReference ib = mock(InfobaseReference.class);
        when(updater.update(eq(project), eq(ib), eq(false), any())).thenThrow(new OperationCanceledException());

        Map<String, Object> out = infobaseJobs.start("restore_infobase_from_dt", "проба", key(), "Demo", null, ctx -> {
            try {
                InfobaseJobs.updateProject(ctx, updater, project, ib, false,
                    new AfterLoad("файл .dt «base.dt» уже загружен в базу DemoIB", List.of("Demo.Склад")));
                fail("expected OperationCanceledException");
            } catch (OperationCanceledException e) {
                ctx.put("cancelled", McpJobs.describe(e));
            }
        }, Map.of());

        McpJob job = jobs.find((String) out.get("jobId")).orElseThrow();
        String text = String.valueOf(job.result().get("cancelled"));
        assertTrue(text, text.startsWith("операция отменена"));
        assertTrue(text, text.contains("«base.dt» уже загружен в базу DemoIB"));
        assertTrue(text, text.contains("Не вызывайте сейчас deploy_project"));
        assertTrue(text, text.contains("Demo.Склад"));
    }

    private List<JobStep> updateProjectSteps(ProjectFromInfobaseUpdater.Outcome outcome) throws Exception {
        ProjectFromInfobaseUpdater updater = mock(ProjectFromInfobaseUpdater.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");
        InfobaseReference ib = mock(InfobaseReference.class);
        when(updater.update(eq(project), eq(ib), eq(false), any())).thenReturn(outcome);

        Map<String, Object> out = infobaseJobs.start("update_project_from_infobase", "проба", key(), "Demo", null,
            ctx -> ctx.put("resolution", InfobaseJobs.updateProject(ctx, updater, project, ib, false).resolution()),
            Map.of());

        McpJob job = jobs.find((String) out.get("jobId")).orElseThrow();
        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        assertEquals(outcome.resolution(), job.result().get("resolution"));
        return job.steps();
    }
}
