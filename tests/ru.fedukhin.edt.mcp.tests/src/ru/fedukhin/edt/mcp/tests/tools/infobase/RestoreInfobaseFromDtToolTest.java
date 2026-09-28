package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.IProject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.jobs.JobLauncher;
import ru.fedukhin.edt.mcp.core.jobs.JobStatus;
import ru.fedukhin.edt.mcp.core.jobs.JobStep;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.core.jobs.StepStatus;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyState;
import ru.fedukhin.edt.mcp.tools.infobase.RestoreInfobaseFromDtTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectsAtRiskException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps;

public class RestoreInfobaseFromDtToolTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final InfobaseTargets targets = mock(InfobaseTargets.class);
    private final SyncV2 syncV2 = mock(SyncV2.class);
    private final ProjectFromInfobaseUpdater updater = mock(ProjectFromInfobaseUpdater.class);
    private final ThickClientOps ops = mock(ThickClientOps.class);
    private final McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
    private final IProject project = project("Demo");
    private final InfobaseReference ib = mock(InfobaseReference.class);
    private final String ibName = "RestoreIB-" + UUID.randomUUID();
    private Path dt;

    private static IProject project(String name) {
        IProject p = mock(IProject.class);
        when(p.getName()).thenReturn(name);
        return p;
    }

    private IExtensionProject extension(String projectName, String extensionName) {
        IExtensionProject ext = mock(IExtensionProject.class);
        IProject p = project(projectName);
        when(ext.getProject()).thenReturn(p);
        Configuration c = mock(Configuration.class);
        when(c.getName()).thenReturn(extensionName);
        when(ext.getConfiguration()).thenReturn(c);
        when(targets.belongsTo(p, ib)).thenReturn(true);
        return ext;
    }

    private RestoreInfobaseFromDtTool tool() throws Exception {
        dt = tmp.newFile("base.dt").toPath();
        when(ib.getName()).thenReturn(ibName);
        when(ib.getUuid()).thenReturn(UUID.randomUUID());
        when(targets.openProject("Demo")).thenReturn(project);
        when(targets.configurationProject(project)).thenReturn(mock(IConfigurationProject.class));
        when(targets.resolve(project, null, false)).thenReturn(new InfobaseTargets.Target(ib, null));
        when(targets.extensionProjects(project)).thenReturn(List.of());
        when(syncV2.projectsAtRisk(any(), any())).thenReturn(List.of());
        when(updater.update(any(), any(), anyBoolean(), any())).thenReturn(new ProjectFromInfobaseUpdater.Outcome("CHANGES_RESOLVED", 1L));
        when(ops.listExtensions(any(), any(), any())).thenReturn(List.of());
        return new RestoreInfobaseFromDtTool(targets, syncV2, updater, ops,
            new InfobaseJobs(() -> jobs, Duration.ofMillis(300)));
    }

    private Map<String, Object> args(Object... extra) {
        Map<String, Object> args = new HashMap<>();
        args.put("project", "Demo");
        args.put("dtFile", dt.toString());
        for (int i = 0; i < extra.length; i += 2) args.put((String) extra[i], extra[i + 1]);
        return args;
    }

    private McpJob job(Map<String, Object> out) {
        return jobs.find((String) out.get("jobId")).orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void happy_restoresThenUpdatesConfigurationAndMatchingExtensions() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        IExtensionProject sklad = extension("Beta.Склад", "Beta_Склад");
        IExtensionProject gone = extension("Beta.Старое", "Старое");
        when(targets.extensionProjects(project)).thenReturn(List.of(sklad, gone));
        when(ops.listExtensions(any(), any(), any())).thenReturn(List.of("BETA_СКЛАД", "Новое"));
        PrivacyState.flags().setFlag(ibName, false);

        McpJob job = job(tool.call(args()));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        InOrder order = inOrder(ops, updater);
        order.verify(ops).restoreDt(eq(project), eq(ib), eq(dt), any());
        order.verify(updater).update(eq(project), eq(ib), anyBoolean(), any());
        order.verify(updater).update(eq(sklad.getProject()), eq(ib), anyBoolean(), any());
        verify(updater, never()).update(eq(gone.getProject()), any(), anyBoolean(), any());
        assertEquals(List.of("Новое"), job.result().get("extensionsWithoutProject"));
        assertEquals(List.of("Beta.Старое"), job.result().get("projectsWithoutExtension"));
        assertEquals(true, job.result().get("piiFlagReset"));
        assertTrue("флаг ПДн сброшен в fail-closed", PrivacyState.flags().containsRealPersonalData(ibName));
        List<Map<String, Object>> projects = (List<Map<String, Object>>) job.result().get("projects");
        assertEquals(3, projects.size());
    }

    /**
     * L2: проекты расширений в EDT 2026.1 своей связи с базой не имеют — они следуют за родительской
     * конфигурацией. Выбор по «связан с базой» не находил ни одного проекта, и сценарий 3 их не
     * обновлял. Теперь берутся проекты без своей связи или связанные с этой базой; связанные только с
     * другими базами пропускаются и перечисляются в результате.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void extensionProjectOfOtherInfobase_skipped_andReported() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        IExtensionProject sklad = extension("Beta.Склад", "Beta_Склад");
        IExtensionProject test = extension("Beta.Тест", "Beta_Тест");
        when(targets.belongsTo(test.getProject(), ib)).thenReturn(false);
        when(targets.extensionProjects(project)).thenReturn(List.of(sklad, test));
        when(ops.listExtensions(any(), any(), any())).thenReturn(List.of("Beta_Склад", "Beta_Тест"));

        McpJob job = job(tool.call(args()));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        verify(updater).update(eq(sklad.getProject()), eq(ib), anyBoolean(), any());
        verify(updater, never()).update(eq(test.getProject()), any(), anyBoolean(), any());
        assertEquals(List.of("Beta.Тест"), job.result().get("extensionProjectsOfOtherInfobases"));
        ArgumentCaptor<Collection<IProject>> checked = ArgumentCaptor.forClass((Class) Collection.class);
        verify(syncV2).projectsAtRisk(checked.capture(), eq(ib));
        assertFalse("проект чужой базы не перезаписывается — и не проверяется", checked.getValue().contains(test.getProject()));
    }

    /**
     * O1 (fix round 5): правка, которую модель EDT ещё не импортировала (~4 с после write_module), синхронной
     * проверке не видна. Задание первым шагом ждёт модели проекта конфигурации и выбранных проектов расширений,
     * проверяет их заново и падает ДО резервной выгрузки и загрузки .dt — база не тронута.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void projectDirtyOnlyAfterModelWait_jobFailsBeforeTouchingInfobase() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        IExtensionProject sklad = extension("Beta.Склад", "Beta_Склад");
        when(targets.extensionProjects(project)).thenReturn(List.of(sklad));
        when(updater.projectsAtRiskAfterModelSync(any(), eq(ib)))
            .thenReturn(List.of("Demo (есть правки, не залитые в базу)"));
        Path backupTo = tmp.getRoot().toPath().resolve("перед-загрузкой.dt");

        McpJob job = job(tool.call(args("backupTo", backupTo.toString())));

        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("discardProjectChanges"));
        // Fix round 8 (L9): первый шаг — ожидание фоновых проверок EDT, затем проверка проектов.
        assertEquals(InfobaseJobs.EDT_CHECKS_STEP, job.steps().get(0).name());
        assertEquals("check-projects", job.steps().get(1).name());
        ArgumentCaptor<Collection<IProject>> checked = ArgumentCaptor.forClass((Class) Collection.class);
        verify(updater).projectsAtRiskAfterModelSync(checked.capture(), eq(ib));
        assertEquals(List.of(project, sklad.getProject()), List.copyOf(checked.getValue()));
        verify(ops, never()).backupDt(any(), any(), any(), any());
        verify(ops, never()).restoreDt(any(), any(), any(), any());
        verify(updater, never()).update(any(), any(), anyBoolean(), any());
    }

    /**
     * F4 (fix round 6): загрузка .dt состояние синхронизации EDT НЕ сбрасывает, а её быстрая проверка по
     * идентификатору поколения данных могла ответить NO_CHANGES, не сравнивая конфигурацию. Поэтому флаг вызова
     * доходит до обновления каждого проекта как есть: false — проверка проекта и честное сравнение базы с
     * записанным состоянием, true — полная замена.
     */
    @Test
    public void discardFlag_isPassedToEveryProjectUpdate() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        IExtensionProject sklad = extension("Beta.Склад", "Beta_Склад");
        when(targets.extensionProjects(project)).thenReturn(List.of(sklad));
        when(ops.listExtensions(any(), any(), any())).thenReturn(List.of("Beta_Склад"));

        assertEquals(JobStatus.SUCCEEDED, job(tool.call(args())).status());
        verify(updater).update(eq(project), eq(ib), eq(false), any());
        verify(updater).update(eq(sklad.getProject()), eq(ib), eq(false), any());

        assertEquals(JobStatus.SUCCEEDED, job(tool.call(args("discardProjectChanges", true))).status());
        verify(updater).update(eq(project), eq(ib), eq(true), any());
        verify(updater).update(eq(sklad.getProject()), eq(ib), eq(true), any());
    }

    /**
     * Fix round 6b: без discardProjectChanges обновление проекта конфигурации может отказать уже ПОСЛЕ загрузки
     * .dt (правка, сделанная во время загрузки). Общий совет «сначала залейте правки (deploy_project)» тут
     * разрушителен — заливка заменила бы только что загруженную конфигурацию. И шаг, и итог задания несут свой
     * текст: .dt уже загружен, проект не обновлён и не тронут, deploy_project сейчас не вызывать, выход —
     * update_project_from_infobase с discardProjectChanges. Задание, как и раньше, падает на этом шаге; проекты
     * расширений не обновлялись — текст их называет.
     */
    @Test
    public void configurationProjectRefusedAfterRestore_loadAwareText_noDeployAdvice() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        IExtensionProject sklad = extension("Beta.Склад", "Beta_Склад");
        when(targets.extensionProjects(project)).thenReturn(List.of(sklad));
        when(updater.update(eq(project), eq(ib), eq(false), any()))
            .thenThrow(new ProjectsAtRiskException(List.of("Demo (есть правки, не залитые в базу)")));

        McpJob job = job(tool.call(args()));

        assertEquals(JobStatus.FAILED, job.status());
        verify(ops).restoreDt(eq(project), eq(ib), eq(dt), any());
        verify(updater, never()).update(eq(sklad.getProject()), any(), anyBoolean(), any());
        JobStep step = job.steps().stream().filter(s -> s.name().equals("update-project Demo")).findFirst().orElseThrow();
        assertEquals(StepStatus.FAILED, step.status());
        for (String text : List.of(job.error(), step.message())) {
            assertTrue(text, text.contains("«base.dt» уже загружен в базу " + ibName));
            assertTrue(text, text.contains("Demo (есть правки, не залитые в базу)"));
            assertTrue(text, text.contains("Содержимое проекта не тронуто"));
            assertTrue(text, text.contains("Не вызывайте сейчас deploy_project"));
            assertTrue(text, text.contains("update_project_from_infobase с discardProjectChanges: true"));
            assertTrue(text, text.contains("Beta.Склад"));
            assertFalse(text, text.contains("сначала залейте их в базу (deploy_project)"));
        }
    }

    /** Fix round 6b: то же для проекта расширения — запись FAILED со своим текстом, остальные идут дальше. */
    @Test
    @SuppressWarnings("unchecked")
    public void extensionProjectRefusedAfterRestore_failedEntryWithLoadAwareText_othersContinue() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        IExtensionProject first = extension("Beta.Первое", "Первое");
        IExtensionProject second = extension("Beta.Второе", "Второе");
        when(targets.extensionProjects(project)).thenReturn(List.of(first, second));
        when(ops.listExtensions(any(), any(), any())).thenReturn(List.of("Первое", "Второе"));
        when(updater.update(eq(first.getProject()), eq(ib), eq(false), any()))
            .thenThrow(new ProjectsAtRiskException(List.of("Beta.Первое (есть правки, не залитые в базу)")));

        McpJob job = job(tool.call(args()));

        assertEquals(JobStatus.FAILED, job.status());
        verify(updater).update(eq(second.getProject()), eq(ib), eq(false), any());
        List<Map<String, Object>> projects = (List<Map<String, Object>>) job.result().get("projects");
        Map<String, Object> refused = projects.stream().filter(p -> "Beta.Первое".equals(p.get("project")))
            .findFirst().orElseThrow();
        assertEquals("FAILED", refused.get("status"));
        String message = String.valueOf(refused.get("message"));
        assertTrue(message, message.contains("«base.dt» уже загружен в базу " + ibName));
        assertTrue(message, message.contains("Beta.Первое (есть правки, не залитые в базу)"));
        assertTrue(message, message.contains("Не вызывайте сейчас deploy_project"));
        assertFalse(message, message.contains("сначала залейте их в базу (deploy_project)"));
        assertEquals("OK", projects.stream().filter(p -> "Beta.Второе".equals(p.get("project")))
            .findFirst().orElseThrow().get("status"));
    }

    /** С discardProjectChanges повторной проверки в задании нет: содержимое проектов разрешено заменить. */
    @Test
    public void discardProjectChanges_skipsInJobCheck() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();

        McpJob job = job(tool.call(args("discardProjectChanges", true)));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        verify(updater, never()).projectsAtRiskAfterModelSync(any(), any());
        verify(ops).restoreDt(eq(project), eq(ib), eq(dt), any());
    }

    @Test
    public void credentials_storedAfterRestore_beforeProjectUpdate_andPasswordNeverEchoed() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();

        Map<String, Object> out = tool.call(args("user", "Админ", "password", "s3cr3t-pw"));
        McpJob job = job(out);

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        InOrder order = inOrder(ops, updater);
        order.verify(ops).restoreDt(eq(project), eq(ib), eq(dt), any());
        order.verify(ops).storeCredentials(ib, "Админ", "s3cr3t-pw");
        order.verify(updater).update(eq(project), eq(ib), anyBoolean(), any());
        assertFalse(String.valueOf(out), String.valueOf(out).contains("s3cr3t-pw"));
        assertFalse(String.valueOf(job.toMap()), String.valueOf(job.toMap()).contains("s3cr3t-pw"));
    }

    @Test
    public void backup_isTakenBeforeRestore() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        Path backup = tmp.getRoot().toPath().resolve("before.dt");

        McpJob job = job(tool.call(args("backupTo", backup.toString())));

        assertEquals(JobStatus.SUCCEEDED, job.status());
        InOrder order = inOrder(ops);
        order.verify(ops).backupDt(eq(project), eq(ib), eq(backup), any());
        order.verify(ops).restoreDt(eq(project), eq(ib), eq(dt), any());
        assertEquals(backup.toString(), job.result().get("backup"));
    }

    /**
     * Загрузка .dt может прерваться (отмена, лимит, сбой на середине), а база к тому моменту уже
     * хранит рабочие данные: флаг ПДн сбрасывается ДО загрузки — и по имени, и по UUID (оба ключа
     * читает query_event_log).
     */
    @Test
    public void piiFlag_resetByNameAndUuid_beforeRestore_evenWhenRestoreFails() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        UUID uuid = UUID.randomUUID();
        when(ib.getUuid()).thenReturn(uuid);
        PrivacyState.flags().setFlag(ibName, false);
        PrivacyState.flags().setFlag(uuid.toString(), false);
        AtomicReference<Boolean> byNameAtRestore = new AtomicReference<>();
        AtomicReference<Boolean> byUuidAtRestore = new AtomicReference<>();
        doAnswer(inv -> {
            byNameAtRestore.set(PrivacyState.flags().containsRealPersonalData(ibName));
            byUuidAtRestore.set(PrivacyState.flags().containsRealPersonalData(uuid.toString()));
            throw new ToolException("ошибка: загрузка .dt в базу — обрыв связи с СУБД");
        }).when(ops).restoreDt(any(), any(), any(), any());

        McpJob job = job(tool.call(args()));

        assertEquals(JobStatus.FAILED, job.status());
        assertEquals("флаг по имени сброшен до загрузки .dt", Boolean.TRUE, byNameAtRestore.get());
        assertEquals("флаг по UUID сброшен до загрузки .dt", Boolean.TRUE, byUuidAtRestore.get());
        assertTrue(PrivacyState.flags().containsRealPersonalData(ibName));
        assertTrue(PrivacyState.flags().containsRealPersonalData(uuid.toString()));
        assertEquals(true, job.result().get("piiFlagReset"));
    }

    @Test
    public void backupToNotDt_refused() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        Path notDt = tmp.getRoot().toPath().resolve("before.zip");
        try {
            tool.call(args("backupTo", notDt.toString()));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("'backupTo': ожидается файл .dt, получен before.zip"));
        }
        verify(ops, never()).restoreDt(any(), any(), any(), any());
    }

    @Test
    public void backupToUpperCaseDt_accepted() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        Path backup = tmp.getRoot().toPath().resolve("BEFORE.DT");

        McpJob job = job(tool.call(args("backupTo", backup.toString())));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        verify(ops).backupDt(eq(project), eq(ib), eq(backup), any());
    }

    /** Модель проекта расширения ещё не загружена — в шаге не «'null'», а понятная причина. */
    @Test
    public void extensionProjectWithoutLoadedModel_skippedWithReason() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        IExtensionProject unloaded = mock(IExtensionProject.class);
        IProject unloadedProject = project("Beta.Незагруженное");
        when(unloaded.getProject()).thenReturn(unloadedProject);
        when(unloaded.getConfiguration()).thenReturn(null);
        when(targets.belongsTo(unloadedProject, ib)).thenReturn(true);
        when(targets.extensionProjects(project)).thenReturn(List.of(unloaded));
        when(ops.listExtensions(any(), any(), any())).thenReturn(List.of("Первое"));

        McpJob job = job(tool.call(args()));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        JobStep skipped = job.steps().stream()
            .filter(s -> s.name().equals("update-project Beta.Незагруженное"))
            .findFirst().orElseThrow();
        assertEquals(StepStatus.SKIPPED, skipped.status());
        assertTrue(skipped.message(), skipped.message().contains(
            "имя расширения проекта Beta.Незагруженное не определено (модель проекта ещё не загружена)"));
        assertFalse(skipped.message(), skipped.message().contains("'null'"));
        verify(updater, never()).update(eq(unloadedProject), any(), anyBoolean(), any());
    }

    @Test
    public void description_explainsRefusalForNewProject() throws Exception {
        String description = tool().description();

        assertTrue(description, description.contains("EDT has no synchronization state for them (e.g. a new project)"));
        assertTrue(description, description.contains("unless discardProjectChanges=true"));
    }

    @Test
    public void existingBackupFile_refused() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        Path existing = tmp.newFile("already.dt").toPath();
        try {
            tool.call(args("backupTo", existing.toString()));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("уже существует"));
        }
        verify(ops, never()).restoreDt(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void dirtyExtensionProject_refusedBeforeRestore_andCheckCoversAllProjects() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        IExtensionProject sklad = extension("Beta.Склад", "Beta_Склад");
        when(targets.extensionProjects(project)).thenReturn(List.of(sklad));
        when(syncV2.projectsAtRisk(any(), any())).thenReturn(List.of("Beta.Склад (есть правки, не залитые в базу)"));
        try {
            tool.call(args());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("discardProjectChanges"));
        }
        ArgumentCaptor<Collection<IProject>> checked = ArgumentCaptor.forClass((Class) Collection.class);
        verify(syncV2).projectsAtRisk(checked.capture(), eq(ib));
        assertTrue(checked.getValue().contains(project));
        assertTrue(checked.getValue().contains(sklad.getProject()));
        verify(ops, never()).restoreDt(any(), any(), any(), any());
    }

    @Test
    public void restoreFailure_failsJob_andLeavesProjectsUntouched() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        doThrow(new ToolException("ошибка: загрузка .dt в базу — База заблокирована"))
            .when(ops).restoreDt(any(), any(), any(), any());

        McpJob job = job(tool.call(args()));

        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("База заблокирована"));
        // Проекты не тронуты: из обращений к updater — только проверка check-projects (чтение), обновлений нет.
        verify(updater, never()).update(any(), any(), anyBoolean(), any());
    }

    @Test
    public void extensionFailure_doesNotStopOtherExtensions() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        IExtensionProject first = extension("Beta.Первое", "Первое");
        IExtensionProject second = extension("Beta.Второе", "Второе");
        when(targets.extensionProjects(project)).thenReturn(List.of(first, second));
        when(ops.listExtensions(any(), any(), any())).thenReturn(List.of("Первое", "Второе"));
        when(updater.update(eq(first.getProject()), any(), anyBoolean(), any())).thenThrow(new ToolException("сбой первого"));

        McpJob job = job(tool.call(args()));

        assertEquals(JobStatus.FAILED, job.status());
        verify(updater).update(eq(second.getProject()), eq(ib), anyBoolean(), any());
    }

    @Test
    public void missingDtFile_refused() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        try {
            tool.call(args("dtFile", tmp.getRoot().toPath().resolve("нет.dt").toString()));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("не найден"));
        }
    }

    /**
     * Предупреждение обновления после загрузки .dt (fix round 9, m2) несёт и указание «после загрузки»: .dt уже
     * загружен, deploy_project сейчас не вызывать, как забрать базу в проект.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void projectStillDirtyAfterUpdate_markedWarning() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        when(updater.update(eq(project), any(), anyBoolean(), any()))
            .thenReturn(new ProjectFromInfobaseUpdater.Outcome("CHANGES_RESOLVED", 1L, "правки проекта Demo остались"));

        McpJob job = job(tool.call(args()));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        Map<String, Object> configuration = ((List<Map<String, Object>>) job.result().get("projects")).get(0);
        assertEquals("WARNING", configuration.get("status"));
        assertEquals("CHANGES_RESOLVED", configuration.get("resolution"));
        String message = String.valueOf(configuration.get("message"));
        assertTrue(message, message.startsWith("правки проекта Demo остались"));
        assertTrue(message, message.contains("«base.dt» уже загружен в базу " + ibName));
        assertTrue(message, message.contains("не вызывайте сейчас deploy_project"));
        assertTrue(message, message.contains("update_project_from_infobase с discardProjectChanges: true"));
    }

    /**
     * Fix round 9 (I2): отказ ДО загрузки .dt при вызове — свой текст: загрузка заменит конфигурацию и данные базы, а
     * затем проекты; правки сохранить (git) и, если нужна текущая база, backupTo; повторить с флагом. Совета залить
     * нет: загрузка заменила бы залитое.
     */
    @Test
    public void refusedAtCall_beforeLoadText_noDeployAdvice() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        when(syncV2.projectsAtRisk(any(), any())).thenReturn(List.of("Demo (есть правки, не залитые в базу)"));
        try {
            tool.call(args());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertBeforeLoadText(e.getMessage());
        }
        verify(ops, never()).restoreDt(any(), any(), any(), any());
    }

    /** Fix round 9 (I2): тот же текст — у отказа шага check-projects в задании. */
    @Test
    public void refusedAtCheckProjects_beforeLoadText_noDeployAdvice() throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        when(updater.projectsAtRiskAfterModelSync(any(), eq(ib)))
            .thenReturn(List.of("Demo (есть правки, не залитые в базу)"));

        McpJob job = job(tool.call(args()));

        assertEquals(JobStatus.FAILED, job.status());
        assertBeforeLoadText(job.error());
        verify(ops, never()).restoreDt(any(), any(), any(), any());
    }

    private void assertBeforeLoadText(String text) {
        assertTrue(text, text.contains("загрузка .dt «base.dt» заменит всю конфигурацию и данные базы " + ibName));
        assertTrue(text, text.contains("Demo (есть правки, не залитые в базу)"));
        assertTrue(text, text.contains("git"));
        assertTrue(text, text.contains("backupTo"));
        assertTrue(text, text.contains("повторите с discardProjectChanges: true"));
        assertFalse(text, text.contains("deploy_project"));
    }

    /**
     * Fix round 9 (m2): обновление проекта конфигурации после загрузки .dt упало не отказом O1, а по другой причине —
     * текст всё равно несёт указание «после загрузки» (deploy_project не вызывать, как забрать базу) и называет
     * проекты расширений, которые задание не обновило; исходная причина — в тексте.
     */
    @Test
    public void configurationProjectFailsAfterRestore_otherReason_afterLoadGuidanceAndNotUpdatedExtensions()
            throws Exception {
        RestoreInfobaseFromDtTool tool = tool();
        IExtensionProject sklad = extension("Beta.Склад", "Beta_Склад");
        when(targets.extensionProjects(project)).thenReturn(List.of(sklad));
        when(updater.update(eq(project), eq(ib), anyBoolean(), any())).thenThrow(new ToolException(
            "обновление проекта Demo из базы " + ibName + " не выполнено: Ошибка получения списка изменений"));

        McpJob job = job(tool.call(args()));

        assertEquals(JobStatus.FAILED, job.status());
        String text = job.error();
        assertTrue(text, text.contains("«base.dt» уже загружен в базу " + ibName));
        assertTrue(text, text.contains("Ошибка получения списка изменений"));
        assertTrue(text, text.contains("Не вызывайте сейчас deploy_project"));
        assertTrue(text, text.contains("update_project_from_infobase с discardProjectChanges: true"));
        assertTrue(text, text.contains("Beta.Склад"));
    }
}
