package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.InOrder;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.jobs.JobLauncher;
import ru.fedukhin.edt.mcp.core.jobs.JobStatus;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.tools.infobase.UpdateExtensionsFromCfeTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectsAtRiskException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps;

public class UpdateExtensionsFromCfeToolTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final InfobaseTargets targets = mock(InfobaseTargets.class);
    private final SyncV2 syncV2 = mock(SyncV2.class);
    private final ProjectFromInfobaseUpdater updater = mock(ProjectFromInfobaseUpdater.class);
    private final ThickClientOps ops = mock(ThickClientOps.class);
    private final McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
    private final IProject project = mock(IProject.class);
    private final InfobaseReference ib = mock(InfobaseReference.class);

    private UpdateExtensionsFromCfeTool tool() throws Exception {
        when(project.getName()).thenReturn("Beta");
        when(ib.getName()).thenReturn("Beta");
        when(ib.getUuid()).thenReturn(UUID.randomUUID());
        when(targets.openProject("Beta")).thenReturn(project);
        when(targets.configurationProject(project)).thenReturn(mock(IConfigurationProject.class));
        when(targets.resolve(project, null, false)).thenReturn(new InfobaseTargets.Target(ib, null));
        when(targets.extensionProject(eq(project), anyString())).thenReturn(Optional.empty());
        when(syncV2.projectsAtRisk(any(), any())).thenReturn(List.of());
        when(updater.update(any(), any(), anyBoolean(), any())).thenReturn(new ProjectFromInfobaseUpdater.Outcome("CHANGES_RESOLVED", 1L));
        return new UpdateExtensionsFromCfeTool(targets, syncV2, updater, ops,
            new InfobaseJobs(() -> jobs, Duration.ofMillis(300)));
    }

    /** @param belongs проект расширения принадлежит базе: без своей связи или связан с ней (L2) */
    private IExtensionProject extensionProject(String projectName, String extensionName, boolean belongs) {
        IExtensionProject ext = mock(IExtensionProject.class);
        IProject p = mock(IProject.class);
        when(p.getName()).thenReturn(projectName);
        when(ext.getProject()).thenReturn(p);
        when(targets.extensionProject(project, extensionName)).thenReturn(Optional.of(ext));
        when(targets.belongsTo(p, ib)).thenReturn(belongs);
        return ext;
    }

    private McpJob job(Map<String, Object> out) {
        return jobs.find((String) out.get("jobId")).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(McpJob job) {
        return (List<Map<String, Object>>) job.result().get("extensions");
    }

    @Test
    public void happy_loadsAppliesAndUpdatesProject_warnsWhenProjectMissing() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path sklad = tmp.newFile("Beta_Склад.cfe").toPath();
        Path fresh = tmp.newFile("Новое.cfe").toPath();
        IExtensionProject skladProject = extensionProject("Beta.Склад", "Beta_Склад", true);

        McpJob job = job(tool.call(Map.of("project", "Beta",
            "extensions", List.of(Map.of("file", sklad.toString()), Map.of("file", fresh.toString())))));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        InOrder order = inOrder(ops, updater);
        order.verify(ops).loadExtension(eq(project), eq(ib), eq(sklad), eq("Beta_Склад"), any());
        order.verify(ops).applyExtension(eq(project), eq(ib), eq("Beta_Склад"), any());
        order.verify(updater).update(eq(skladProject.getProject()), eq(ib), anyBoolean(), any());
        order.verify(ops).loadExtension(eq(project), eq(ib), eq(fresh), eq("Новое"), any());
        List<Map<String, Object>> results = results(job);
        assertEquals("OK", results.get(0).get("status"));
        assertEquals(true, results.get(0).get("projectUpdated"));
        assertEquals("WARNING", results.get(1).get("status"));
        assertTrue(String.valueOf(results.get(1).get("message")).contains("создайте пустой проект"));
    }

    /**
     * Нет проекта расширения: пустой проект, созданный по подсказке, ещё не синхронизирован с
     * базой, так что повторный вызов откажет, а deploy_project залил бы пустой проект В базу.
     * Подсказка обязана вести к update_project_from_infobase с discardProjectChanges: true.
     */
    @Test
    public void missingProject_hintLeadsToDiscardFlag_notToDeploy() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path fresh = tmp.newFile("Новое.cfe").toPath();

        McpJob job = job(tool.call(Map.of("project", "Beta", "extensions", List.of(Map.of("file", fresh.toString())))));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        String message = String.valueOf(results(job).get(0).get("message"));
        assertTrue(message, message.contains("проекта расширения 'Новое' нет в рабочей области"));
        assertTrue(message, message.contains("расширение в базу уже загружено"));
        assertTrue(message, message.contains("create_project type=extension, parentConfigurationName=Beta"));
        assertTrue(message, message.contains("update_project_from_infobase"));
        assertTrue(message, message.contains("discardProjectChanges: true"));
        assertTrue(message, message.contains("база берётся у родительской конфигурации"));
        assertFalse("проект расширения с базой не связать — EDT связывает базу только с конфигурацией: " + message,
            message.contains("associate_infobase"));
        assertFalse(message, message.contains("deploy_project"));
    }

    /**
     * L2: проект расширения, связанный только с другой базой, из этой базы не обновляется: шаг
     * WARNING с причиной; в проверку незалитых правок он не попадает — его не перезаписывают.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void extensionProjectOfOtherInfobase_skippedWithWarning() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        IExtensionProject skladProject = extensionProject("Beta.Склад", "Beta_Склад", false);
        when(targets.associatedInfobases(skladProject.getProject())).thenReturn(List.of("BetaTest"));

        McpJob job = job(tool.call(Map.of("project", "Beta", "extensions", List.of(Map.of("file", file.toString())))));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        verify(ops).loadExtension(eq(project), eq(ib), eq(file), eq("Beta_Склад"), any());
        verify(updater, never()).update(eq(skladProject.getProject()), any(), anyBoolean(), any());
        verify(syncV2, never()).projectsAtRisk(any(), any());
        verify(targets, never()).associate(any(), any(), anyBoolean());
        Map<String, Object> result = results(job).get(0);
        assertEquals("WARNING", result.get("status"));
        assertEquals("Beta.Склад", result.get("project"));
        String message = String.valueOf(result.get("message"));
        assertTrue(message, message.contains("BetaTest"));
        assertTrue(message, message.contains("не обновлялся"));
    }

    @Test
    public void description_explainsRefusalForNewProject() throws Exception {
        String description = tool().description();

        assertTrue(description, description.contains("EDT has no synchronization state for them (e.g. a new project)"));
        assertTrue(description, description.contains("unless discardProjectChanges=true"));
    }

    @Test
    public void credentials_storedBeforeLoading_andPasswordNeverEchoed() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();

        Map<String, Object> out = tool.call(Map.of("project", "Beta", "user", "Админ", "password", "s3cr3t-pw",
            "extensions", List.of(Map.of("file", file.toString()))));
        McpJob job = job(out);

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        InOrder order = inOrder(ops);
        order.verify(ops).storeCredentials(ib, "Админ", "s3cr3t-pw");
        order.verify(ops).loadExtension(eq(project), eq(ib), eq(file), eq("Beta_Склад"), any());
        assertFalse(String.valueOf(out), String.valueOf(out).contains("s3cr3t-pw"));
        assertFalse(String.valueOf(job.toMap()), String.valueOf(job.toMap()).contains("s3cr3t-pw"));
    }

    @Test
    public void explicitName_overridesFileName() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("export-2026-09-26.cfe").toPath();

        tool.call(Map.of("project", "Beta", "updateProjects", false,
            "extensions", List.of(Map.of("file", file.toString(), "name", "Beta_Логистика"))));

        verify(ops).loadExtension(eq(project), eq(ib), eq(file), eq("Beta_Логистика"), any());
    }

    /**
     * L2: связать проект расширения с базой нельзя (EDT 2026.1: «Infobase X is already associated with
     * project P» — база связывается только с конфигурацией), да и не нужно: проект без своей связи
     * следует за родительской конфигурацией. Обновляется с явной базой, без associate.
     */
    @Test
    public void extensionProjectWithoutOwnAssociation_updatedWithoutAssociating() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        IExtensionProject skladProject = extensionProject("Beta.Склад", "Beta_Склад", true);

        McpJob job = job(tool.call(Map.of("project", "Beta", "discardProjectChanges", true,
            "extensions", List.of(Map.of("file", file.toString())))));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        verify(updater).update(eq(skladProject.getProject()), eq(ib), anyBoolean(), any());
        verify(targets, never()).associate(any(), any(), anyBoolean());
        assertEquals("OK", results(job).get(0).get("status"));
    }

    @Test
    public void failingExtension_doesNotStopOthers_andFailsJob() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path broken = tmp.newFile("Сломанное.cfe").toPath();
        Path good = tmp.newFile("Хорошее.cfe").toPath();
        doThrow(new ToolException("ошибка: загрузка расширения Сломанное — несовместимо с конфигурацией"))
            .when(ops).loadExtension(any(), any(), eq(broken), any(), any());

        McpJob job = job(tool.call(Map.of("project", "Beta", "updateProjects", false,
            "extensions", List.of(Map.of("file", broken.toString()), Map.of("file", good.toString())))));

        assertEquals(JobStatus.FAILED, job.status());
        verify(ops).loadExtension(eq(project), eq(ib), eq(good), eq("Хорошее"), any());
        assertEquals("FAILED", results(job).get(0).get("status"));
        assertEquals("OK", results(job).get(1).get("status"));
    }

    @Test
    public void updateProjectsFalse_touchesOnlyInfobase_andWorksWithoutV2() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();

        McpJob job = job(tool.call(Map.of("project", "Beta", "updateProjects", false,
            "extensions", List.of(Map.of("file", file.toString())))));

        assertEquals(JobStatus.SUCCEEDED, job.status());
        verifyZeroInteractions(updater);
        verify(syncV2, never()).requireAvailable();
    }

    @Test
    public void dirtyExtensionProject_refusedBeforeLoading() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        extensionProject("Beta.Склад", "Beta_Склад", true);
        when(syncV2.projectsAtRisk(any(), any())).thenReturn(List.of("Beta.Склад (есть правки, не залитые в базу)"));
        try {
            tool.call(Map.of("project", "Beta", "extensions", List.of(Map.of("file", file.toString()))));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("discardProjectChanges"));
        }
        verify(ops, never()).loadExtension(any(), any(), any(), any(), any());
    }

    /**
     * O1 (fix round 5): правка, которую модель EDT ещё не импортировала (~4 с после write_module), синхронной
     * проверке не видна. Задание проверяет проекты расширений заново после ожидания модели и падает ДО первой
     * загрузки .cfe — ничего разрушительного не выполняется.
     */
    @Test
    public void extensionProjectDirtyOnlyAfterModelWait_jobFailsBeforeLoading() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        IExtensionProject sklad = extensionProject("Beta.Склад", "Beta_Склад", true);
        when(updater.projectsAtRiskAfterModelSync(any(), eq(ib)))
            .thenReturn(List.of("Beta.Склад (есть правки, не залитые в базу)"));

        McpJob job = job(tool.call(Map.of("project", "Beta", "extensions", List.of(Map.of("file", file.toString())))));

        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("discardProjectChanges"));
        // Fix round 8 (L9): первый шаг — ожидание фоновых проверок EDT, затем проверка проектов.
        assertEquals(InfobaseJobs.EDT_CHECKS_STEP, job.steps().get(0).name());
        assertEquals("check-projects", job.steps().get(1).name());
        verify(updater).projectsAtRiskAfterModelSync(eq(List.of(sklad.getProject())), eq(ib));
        verify(ops, never()).loadExtension(any(), any(), any(), any(), any());
        verify(ops, never()).applyExtension(any(), any(), any(), any());
        verify(updater, never()).update(any(), any(), anyBoolean(), any());
    }

    /**
     * F4 (fix round 6): идентификатор поколения расширения после {@code /LoadCfg -Extension} неизвестен — флаг
     * вызова доходит до обновления проекта расширения как есть (false — проверка и честное сравнение, true —
     * полная замена).
     */
    @Test
    public void discardFlag_isPassedToExtensionProjectUpdate() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        IExtensionProject sklad = extensionProject("Beta.Склад", "Beta_Склад", true);

        assertEquals(JobStatus.SUCCEEDED, job(tool.call(Map.of("project", "Beta",
            "extensions", List.of(Map.of("file", file.toString()))))).status());
        verify(updater).update(eq(sklad.getProject()), eq(ib), eq(false), any());

        assertEquals(JobStatus.SUCCEEDED, job(tool.call(Map.of("project", "Beta", "discardProjectChanges", true,
            "extensions", List.of(Map.of("file", file.toString()))))).status());
        verify(updater).update(eq(sklad.getProject()), eq(ib), eq(true), any());
    }

    /**
     * Fix round 6b: без discardProjectChanges обновление проекта расширения может отказать уже ПОСЛЕ загрузки и
     * применения .cfe. Общий совет «сначала залейте правки (deploy_project)» тут разрушителен — заливка заменила бы
     * только что загруженное. Запись FAILED и шаг несут свой текст: расширение (по имени) уже загружено, проект не
     * обновлён и не тронут, deploy_project сейчас не вызывать, выход — update_project_from_infobase с
     * discardProjectChanges.
     */
    @Test
    public void extensionProjectRefusedAfterLoad_failedEntryWithLoadAwareText() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        IExtensionProject sklad = extensionProject("Beta.Склад", "Beta_Склад", true);
        when(updater.update(eq(sklad.getProject()), eq(ib), eq(false), any()))
            .thenThrow(new ProjectsAtRiskException(List.of("Beta.Склад (есть правки, не залитые в базу)")));

        McpJob job = job(tool.call(Map.of("project", "Beta", "extensions", List.of(Map.of("file", file.toString())))));

        assertEquals(JobStatus.FAILED, job.status());
        Map<String, Object> result = results(job).get(0);
        assertEquals("FAILED", result.get("status"));
        assertEquals(true, result.get("loaded"));
        assertEquals(true, result.get("applied"));
        String step = job.steps().stream().filter(s -> s.name().equals("update-project Beta.Склад")).findFirst()
            .orElseThrow().message();
        for (String text : List.of(String.valueOf(result.get("message")), step)) {
            assertTrue(text, text.contains("расширение «Beta_Склад» (файл «Beta_Склад.cfe») уже загружено в базу "
                + "Beta и применено"));
            assertTrue(text, text.contains("Beta.Склад (есть правки, не залитые в базу)"));
            assertTrue(text, text.contains("Содержимое проекта не тронуто"));
            assertTrue(text, text.contains("Не вызывайте сейчас deploy_project"));
            assertTrue(text, text.contains("update_project_from_infobase с discardProjectChanges: true"));
            assertFalse(text, text.contains("сначала залейте их в базу (deploy_project)"));
        }
    }

    /** С discardProjectChanges повторной проверки в задании нет: содержимое проектов разрешено заменить. */
    @Test
    public void discardProjectChanges_skipsInJobCheck() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        extensionProject("Beta.Склад", "Beta_Склад", true);

        McpJob job = job(tool.call(Map.of("project", "Beta", "discardProjectChanges", true,
            "extensions", List.of(Map.of("file", file.toString())))));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        verify(updater, never()).projectsAtRiskAfterModelSync(any(), any());
        verify(ops).loadExtension(eq(project), eq(ib), eq(file), eq("Beta_Склад"), any());
    }

    @Test
    public void duplicateNames_refused() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path a = tmp.newFile("Одно.cfe").toPath();
        Path b = tmp.newFile("другое.cfe").toPath();
        try {
            tool.call(Map.of("project", "Beta", "extensions",
                List.of(Map.of("file", a.toString()), Map.of("file", b.toString(), "name", "ОДНО"))));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("дважды"));
        }
    }

    @Test
    public void notCfeFile_refused() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path dt = tmp.newFile("база.dt").toPath();
        try {
            tool.call(Map.of("project", "Beta", "extensions", List.of(Map.of("file", dt.toString()))));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(".cfe"));
        }
    }

    /** Предупреждение обновления после загрузки .cfe (fix round 9, m2) несёт и указание «после загрузки». */
    @Test
    public void projectStillDirtyAfterUpdate_markedWarning() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        IExtensionProject skladProject = extensionProject("Beta.Склад", "Beta_Склад", true);
        when(updater.update(eq(skladProject.getProject()), any(), anyBoolean(), any())).thenReturn(
            new ProjectFromInfobaseUpdater.Outcome("CHANGES_RESOLVED", 1L, "правки проекта Beta.Склад остались"));

        McpJob job = job(tool.call(Map.of("project", "Beta", "extensions", List.of(Map.of("file", file.toString())))));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        Map<String, Object> result = results(job).get(0);
        assertEquals("WARNING", result.get("status"));
        assertEquals(true, result.get("projectUpdated"));
        String message = String.valueOf(result.get("message"));
        assertTrue(message, message.startsWith("правки проекта Beta.Склад остались"));
        assertTrue(message, message.contains("не вызывайте сейчас deploy_project"));
    }

    /**
     * Fix round 9 (I2): отказ ДО загрузки .cfe при вызове — свой текст: загрузка заменит расширения в базе, а затем
     * проекты; правки сохранить (git) и повторить с флагом. Совета залить нет: загрузка заменила бы залитое.
     */
    @Test
    public void refusedAtCall_beforeLoadText_noDeployAdvice() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        extensionProject("Beta.Склад", "Beta_Склад", true);
        when(syncV2.projectsAtRisk(any(), any())).thenReturn(List.of("Beta.Склад (есть правки, не залитые в базу)"));
        try {
            tool.call(Map.of("project", "Beta", "extensions", List.of(Map.of("file", file.toString()))));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertBeforeLoadText(e.getMessage());
        }
        verify(ops, never()).loadExtension(any(), any(), any(), any(), any());
    }

    /** Fix round 9 (I2): тот же текст — у отказа шага check-projects в задании. */
    @Test
    public void refusedAtCheckProjects_beforeLoadText_noDeployAdvice() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        extensionProject("Beta.Склад", "Beta_Склад", true);
        when(updater.projectsAtRiskAfterModelSync(any(), eq(ib)))
            .thenReturn(List.of("Beta.Склад (есть правки, не залитые в базу)"));

        McpJob job = job(tool.call(Map.of("project", "Beta", "extensions", List.of(Map.of("file", file.toString())))));

        assertEquals(JobStatus.FAILED, job.status());
        assertBeforeLoadText(job.error());
        verify(ops, never()).loadExtension(any(), any(), any(), any(), any());
    }

    private static void assertBeforeLoadText(String text) {
        assertTrue(text, text.contains("загрузка .cfe заменит в базе Beta расширения «Beta_Склад»"));
        assertTrue(text, text.contains("Beta.Склад (есть правки, не залитые в базу)"));
        assertTrue(text, text.contains("git"));
        assertTrue(text, text.contains("повторите с discardProjectChanges: true"));
        assertFalse(text, text.contains("backupTo"));
        assertFalse(text, text.contains("deploy_project"));
    }

    /**
     * Fix round 9 (m2): обновление проекта расширения после загрузки .cfe упало не отказом O1 — запись FAILED всё равно
     * несёт указание «после загрузки» и исходную причину.
     */
    @Test
    public void extensionProjectFailsAfterLoad_otherReason_afterLoadGuidance() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("Beta_Склад.cfe").toPath();
        IExtensionProject sklad = extensionProject("Beta.Склад", "Beta_Склад", true);
        when(updater.update(eq(sklad.getProject()), eq(ib), anyBoolean(), any())).thenThrow(new ToolException(
            "обновление проекта Beta.Склад из базы Beta не выполнено: Ошибка получения списка изменений"));

        McpJob job = job(tool.call(Map.of("project", "Beta", "extensions", List.of(Map.of("file", file.toString())))));

        Map<String, Object> result = results(job).get(0);
        assertEquals("FAILED", result.get("status"));
        String text = String.valueOf(result.get("message"));
        assertTrue(text, text.contains("расширение «Beta_Склад» (файл «Beta_Склад.cfe») уже загружено в базу Beta"));
        assertTrue(text, text.contains("Ошибка получения списка изменений"));
        assertTrue(text, text.contains("Не вызывайте сейчас deploy_project"));
        assertTrue(text, text.contains("update_project_from_infobase с discardProjectChanges: true"));
    }

    /**
     * Fix round 9 (X9, живой прогон): .cfe загружен под именем, отличным от собственного имени расширения, — применение
     * падает «Расширение с таким именем уже существует!», а загруженное, но не применённое расширение остаётся в
     * конфигурации базы. Подсказка: name должно совпадать с собственным именем расширения; что делать с оставшимся.
     */
    @Test
    public void applyFails_extensionNameClash_hintsAboutName() throws Exception {
        UpdateExtensionsFromCfeTool tool = tool();
        Path file = tmp.newFile("SmokeCopy.cfe").toPath();
        doThrow(new ToolException("ошибка: применение расширения SmokeCopy — Расширение с таким именем уже существует!"))
            .when(ops).applyExtension(any(), any(), eq("SmokeCopy"), any());

        McpJob job = job(tool.call(Map.of("project", "Beta", "updateProjects", false,
            "extensions", List.of(Map.of("file", file.toString())))));

        Map<String, Object> result = results(job).get(0);
        assertEquals("FAILED", result.get("status"));
        String text = String.valueOf(result.get("message"));
        assertTrue(text, text.contains("Расширение с таким именем уже существует"));
        assertTrue(text, text.contains("name"));
        assertTrue(text, text.contains("собственным именем расширения"));
        assertTrue(text, text.contains("'SmokeCopy'"));
        assertTrue(text, text.contains("удалите его в конфигураторе"));
    }
}
