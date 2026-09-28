package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import org.mockito.InOrder;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.jobs.JobLauncher;
import ru.fedukhin.edt.mcp.core.jobs.JobStatus;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.tools.infobase.UpdateProjectFromInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps;

public class UpdateProjectFromInfobaseToolTest {

    private final InfobaseTargets targets = mock(InfobaseTargets.class);
    private final SyncV2 syncV2 = mock(SyncV2.class);
    private final ProjectFromInfobaseUpdater updater = mock(ProjectFromInfobaseUpdater.class);
    private final ThickClientOps ops = mock(ThickClientOps.class);
    private final McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
    private final IProject project = mock(IProject.class);
    private final InfobaseReference ib = mock(InfobaseReference.class);

    private UpdateProjectFromInfobaseTool tool() throws Exception {
        when(project.getName()).thenReturn("Demo");
        when(ib.getName()).thenReturn("DemoIB");
        when(ib.getUuid()).thenReturn(UUID.randomUUID()); // своё имя замка на каждый тест
        when(targets.openProject("Demo")).thenReturn(project);
        when(targets.resolve(project, null, false)).thenReturn(new InfobaseTargets.Target(ib, null));
        when(syncV2.projectsAtRisk(any(), any())).thenReturn(List.of());
        return new UpdateProjectFromInfobaseTool(targets, syncV2, updater, ops,
            new InfobaseJobs(() -> jobs, Duration.ofMillis(300)));
    }

    private McpJob job(Map<String, Object> out) {
        return jobs.find((String) out.get("jobId")).orElseThrow();
    }

    @Test
    public void happy_startsJob_updatesProject_andReportsResolution() throws Exception {
        UpdateProjectFromInfobaseTool tool = tool();
        when(updater.update(eq(project), eq(ib), anyBoolean(), any()))
            .thenReturn(new ProjectFromInfobaseUpdater.Outcome("CHANGES_RESOLVED", 1200L));

        Map<String, Object> out = tool.call(Map.of("project", "Demo"));

        assertEquals("DemoIB", out.get("infobase"));
        assertEquals("get_job_status", out.get("poll"));
        McpJob job = job(out);
        assertEquals(JobStatus.SUCCEEDED, job.status());
        assertEquals("CHANGES_RESOLVED", job.result().get("resolution"));
        verify(ops, never()).storeCredentials(any(), any(), any());
        // Fix round 8 (L9): первый шаг — ожидание фоновых проверок EDT.
        assertEquals(InfobaseJobs.EDT_CHECKS_STEP, job.steps().get(0).name());
    }

    @Test
    public void dirtyProject_refusedBeforeJob() throws Exception {
        UpdateProjectFromInfobaseTool tool = tool();
        when(syncV2.projectsAtRisk(any(), any())).thenReturn(List.of("Demo (есть правки, не залитые в базу)"));
        try {
            tool.call(Map.of("project", "Demo"));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("discardProjectChanges"));
        }
        verifyZeroInteractions(updater);
        assertTrue(jobs.list().isEmpty());
    }

    /**
     * Fix round 7 (R7-3): проект не конфигурации и не расширения (например, внешних отчётов и обработок) — отказ
     * до задания: его состояние синхронизации EDT хранит у родительской конфигурации.
     */
    @Test
    public void externalObjectsProject_refusedBeforeJob() throws Exception {
        UpdateProjectFromInfobaseTool tool = tool();
        when(targets.configurationOrExtensionProject(project)).thenThrow(new ToolException(
            "проект 'Demo' — это проект внешних отчётов и обработок: из базы обновляется только проект конфигурации "
                + "или проект расширения"));
        try {
            tool.call(Map.of("project", "Demo", "discardProjectChanges", true));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("внешних отчётов и обработок"));
        }
        verifyZeroInteractions(updater, syncV2);
        assertTrue(jobs.list().isEmpty());
    }

    @Test
    public void description_explainsRefusalForNewProject() throws Exception {
        String description = tool().description();

        assertTrue(description, description.contains("Refuses while the project has changes not deployed to the "
            + "infobase, or EDT has no synchronization state for it (e.g. a new project), unless "
            + "discardProjectChanges=true."));
    }

    /** L2: проект расширения своей связи не имеет — база по умолчанию берётся у родительской конфигурации. */
    @Test
    @SuppressWarnings("unchecked")
    public void infobaseArgument_documentsParentInfobaseForExtensionProject() throws Exception {
        Map<String, Object> properties = (Map<String, Object>) tool().inputSchema().get("properties");
        String infobase = String.valueOf(((Map<String, Object>) properties.get("infobase")).get("description"));

        assertTrue(infobase, infobase.contains("an extension project without its own association uses its parent "
            + "configuration's infobase"));
    }

    @Test
    public void discardProjectChanges_skipsDirtyCheck() throws Exception {
        UpdateProjectFromInfobaseTool tool = tool();
        when(updater.update(any(), any(), anyBoolean(), any()))
            .thenReturn(new ProjectFromInfobaseUpdater.Outcome("CHANGES_RESOLVED", 1L));

        tool.call(Map.of("project", "Demo", "discardProjectChanges", true));

        verify(syncV2, never()).projectsAtRisk(any(), any());
    }

    /**
     * O1 (fix round 5): синхронная проверка в call() — лишь быстрый отказ; окончательная — в задании, после
     * ожидания модели EDT ({@code ProjectFromInfobaseUpdater.update} с {@code discardProjectChanges = false}).
     * Флаг обязан дойти до неё как есть.
     */
    @Test
    public void discardFlag_isPassedToUpdateInsideJob() throws Exception {
        UpdateProjectFromInfobaseTool tool = tool();
        when(updater.update(any(), any(), anyBoolean(), any()))
            .thenReturn(new ProjectFromInfobaseUpdater.Outcome("CHANGES_RESOLVED", 1L));

        assertEquals(JobStatus.SUCCEEDED, job(tool.call(Map.of("project", "Demo"))).status());
        verify(updater).update(eq(project), eq(ib), eq(false), any());

        assertEquals(JobStatus.SUCCEEDED, job(tool.call(Map.of("project", "Demo", "discardProjectChanges", true))).status());
        verify(updater).update(eq(project), eq(ib), eq(true), any());
    }

    /** Отказ задания (правка, увиденная только после ожидания модели) — задание FAILED с текстом отказа. */
    @Test
    public void inJobRefusal_failsJobWithDiscardHint() throws Exception {
        UpdateProjectFromInfobaseTool tool = tool();
        when(updater.update(any(), any(), anyBoolean(), any())).thenThrow(new ToolException(
            SyncV2.atRiskMessage(List.of("Demo (есть правки, не залитые в базу)"))));

        McpJob job = job(tool.call(Map.of("project", "Demo")));

        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("discardProjectChanges"));
    }

    @Test
    public void credentials_areStoredBeforeUpdate() throws Exception {
        UpdateProjectFromInfobaseTool tool = tool();
        when(updater.update(any(), any(), anyBoolean(), any()))
            .thenReturn(new ProjectFromInfobaseUpdater.Outcome("NO_CHANGES", 1L));

        tool.call(Map.of("project", "Demo", "user", "Админ", "password", "secret"));

        InOrder order = inOrder(ops, updater);
        order.verify(ops).storeCredentials(ib, "Админ", "secret");
        order.verify(updater).update(eq(project), eq(ib), eq(false), any());
    }

    @Test
    public void oldEdt_refusedBeforeResolvingInfobase() throws Exception {
        UpdateProjectFromInfobaseTool tool = tool();
        doThrow(new ToolException("инструмент требует 1C:EDT 2026.1 или новее")).when(syncV2).requireAvailable();
        try {
            tool.call(Map.of("project", "Demo"));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("2026.1"));
        }
        verify(targets, never()).resolve(any(), any(), anyBoolean());
    }

    @Test
    public void updateFailure_failsJobWithReason() throws Exception {
        UpdateProjectFromInfobaseTool tool = tool();
        when(updater.update(any(), any(), anyBoolean(), any()))
            .thenThrow(new ToolException("обновление проекта Demo из базы DemoIB не выполнено: нет связи"));

        McpJob job = job(tool.call(Map.of("project", "Demo")));

        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("нет связи"));
    }

    @Test
    public void projectStillDirtyAfterUpdate_reportsWarning() throws Exception {
        UpdateProjectFromInfobaseTool tool = tool();
        when(updater.update(any(), any(), anyBoolean(), any()))
            .thenReturn(new ProjectFromInfobaseUpdater.Outcome("NO_CHANGES", 1L, "правки проекта Demo остались"));

        McpJob job = job(tool.call(Map.of("project", "Demo", "discardProjectChanges", true)));

        assertEquals(JobStatus.SUCCEEDED, job.status());
        assertEquals("правки проекта Demo остались", job.result().get("warning"));
    }
}
