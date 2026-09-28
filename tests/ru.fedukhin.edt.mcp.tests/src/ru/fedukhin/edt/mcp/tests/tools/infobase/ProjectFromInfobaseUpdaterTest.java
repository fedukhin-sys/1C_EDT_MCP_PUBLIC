package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.resource.EdtResourceMetadata;
import com._1c.g5.v8.dt.core.resource.IResourceStoreManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseChangesResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSyncResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateProjectFlow;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncStateJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.HeadlessInfobaseChangesResolver;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectsAtRiskException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps;

public class ProjectFromInfobaseUpdaterTest {

    private final IProject project = mock(IProject.class);
    private final InfobaseReference ref = mock(InfobaseReference.class);
    private final IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
    private final IProgressMonitor monitor = new NullProgressMonitor();
    private final IBmModelManager models = mock(IBmModelManager.class);
    /** Внутренний делегат сервиса синхронизации EDT — у него {@code forceEdtSynchronization} (L8). */
    private final SyncV2Fakes.Delegate delegate = mock(SyncV2Fakes.Delegate.class);
    private final IV8ProjectManager projects = mock(IV8ProjectManager.class);
    private final IDtProject dtProject = mock(IDtProject.class);
    private final IResourceStoreManager resources = mock(IResourceStoreManager.class);
    /**
     * Операции конфигуратора: перед каждым забором обновление закрывает сеанс агента EDT на базе (fix round 7,
     * «шлюз» EDT). По умолчанию ({@code null}) — сеанс закрыт, шлюз открыт.
     */
    private final ThickClientOps ops = mock(ThickClientOps.class);
    /** Фоновые проверки EDT после запуска или открытия (fix round 8, L9). По умолчанию ({@code null}) — ждать нечего. */
    private final EdtSyncStateJobs edtJobs = mock(EdtSyncStateJobs.class);
    /**
     * Как обновление находит службу подписей ресурсов EDT: в бою — {@code ServiceAccess} в момент вызова (её
     * нет в Guice, fix round 4b), в тестах — этот шов; читается при каждом обновлении.
     */
    private Supplier<Object> resourcesLookup = () -> resources;
    /** Подписи ресурсов проекта глазами EDT: {@code getEffectiveResourceMetadata} отдаёт их копию. */
    private final Map<String, EdtResourceMetadata> effective = new HashMap<>();

    @Before
    public void edtProject() {
        IV8Project v8 = mock(IV8Project.class);
        when(projects.getProject(project)).thenReturn(v8);
        when(v8.getDtProject()).thenReturn(dtProject);
        when(resources.getEffectiveResourceMetadata(dtProject)).thenAnswer(inv -> new HashMap<>(effective));
    }

    /** По умолчанию — «чистый» проект (нет предупреждения от dirty-after-update проверки):
     *  тесты, которым нужен именно этот сценарий, используют {@link #updater(IInfobaseSynchronizationStateManager)}. */
    private ProjectFromInfobaseUpdater updater() {
        IInfobaseSynchronizationStateManager stateManager = mock(IInfobaseSynchronizationStateManager.class);
        // any(IProject.class) — не просто any(): IInfobaseSynchronizationStateManager перегружает
        // hasSynchronizationInfo/isProjectDirty и по IDtProject, без явного типа резолюция неоднозначна.
        when(stateManager.hasSynchronizationInfo(any(IProject.class), any())).thenReturn(true);
        when(stateManager.isProjectDirty(any(IProject.class), any())).thenReturn(false);
        return updater(stateManager);
    }

    /** Явный state manager — чтобы тест мог заранее подготовить {@code hasSynchronizationInfo}/
     *  {@code isProjectDirty} на ТОМ ЖЕ моке, который {@code SyncV2} потом закэширует. */
    private ProjectFromInfobaseUpdater updater(IInfobaseSynchronizationStateManager stateManager) {
        return updaterWith(new SyncV2(() -> stateManager));
    }

    private ProjectFromInfobaseUpdater updaterWith(SyncV2 syncV2) {
        when(project.getName()).thenReturn("Demo");
        when(ref.getName()).thenReturn("DemoIB");
        return new ProjectFromInfobaseUpdater(sync, syncV2, projects, models, ops, edtJobs, () -> resourcesLookup.get());
    }

    @Test
    public void update_usesHeadlessResolverAndTrueFlag_likeIde() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_RESOLVED));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        ArgumentCaptor<IInfobaseChangesResolver> resolver = ArgumentCaptor.forClass(IInfobaseChangesResolver.class);
        verify(sync).retrieveInfobaseChanges(eq(project), eq(ref), resolver.capture(), eq(true), eq(monitor));
        assertTrue(resolver.getValue() instanceof HeadlessInfobaseChangesResolver);
        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        verify(project).refreshLocal(IResource.DEPTH_INFINITE, monitor);
    }

    @Test
    public void pendingResolution_waitsForFuture() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        InfobaseSyncResolution pending = new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED);
        pending.setConflictResolution(CompletableFuture.completedFuture(Status.OK_STATUS));
        when(sync.retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any())).thenReturn(pending);

        // CHANGES_NOT_RESOLVED — промежуточный статус на момент возврата из retrieveInfobaseChanges;
        // раз future дождались и оно без ошибки, итог для вызывающего — успех CHANGES_RESOLVED, а не
        // "не решено" (нормализация по whitelist, код-ревью Task 6).
        assertEquals("CHANGES_RESOLVED", updater.update(project, ref, true, monitor).resolution());
    }

    @Test
    public void pendingResolutionCompletesExceptionally_fails() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        InfobaseSyncResolution pending = new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED);
        CompletableFuture<IStatus> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("боевой сбой ассистента"));
        pending.setConflictResolution(future);
        when(sync.retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any())).thenReturn(pending);

        expectFailure(updater, "боевой сбой ассистента");
    }

    @Test
    public void pendingResolutionWithError_fails() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        InfobaseSyncResolution pending = new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED);
        pending.setConflictResolution(CompletableFuture.completedFuture(
            new Status(IStatus.ERROR, "test", "Замещение содержимого проекта не удалось")));
        when(sync.retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any())).thenReturn(pending);

        expectFailure(updater, "Замещение содержимого проекта не удалось");
    }

    @Test
    public void notResolvedWithoutFuture_fails() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        when(sync.retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED));

        expectFailure(updater, "CHANGES_NOT_RESOLVED");
    }

    @Test
    public void ignoredChanges_fail() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        when(sync.retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_IGNORE));

        expectFailure(updater, "CHANGES_IGNORE");
    }

    @Test
    public void synchronizationException_isWrappedWithStatusMessage() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        when(sync.retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any())).thenThrow(
            new InfobaseSynchronizationException(Status.error("Ошибка получения списка изменений в информационной базе")));

        expectFailure(updater, "Ошибка получения списка изменений");
    }

    @Test
    public void noChanges_butProjectStillDirty_warnsWithoutFailing() throws Exception {
        // EDT возвращает NO_CHANGES без вызова нашего обработчика, если не изменилась САМА база —
        // содержимое проекта в этом случае не трогается, даже если в нём остались незалитые правки.
        // Шлюз открыт (fix round 7) и быстрая проверка сброшена (F4), так что NO_CHANGES — итог настоящего
        // сравнения: «база не менялась» — правда. Без discardProjectChanges проект до забора был чист (O1 и повторная
        // проверка прямо перед забором, m1 fix round 9), правка появилась, пока шло обновление.
        IInfobaseSynchronizationStateManager stateManager = mock(IInfobaseSynchronizationStateManager.class);
        when(stateManager.hasSynchronizationInfo(project, ref)).thenReturn(true);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(false, false, true);
        SyncV2 rechecked = spy(new SyncV2(() -> stateManager));
        doReturn(new SyncV2.Recheck(true, null)).when(rechecked).forceRecheck(any(), any(), anyBoolean());
        ProjectFromInfobaseUpdater updater = updaterWith(rechecked);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, false, monitor);

        assertEquals("NO_CHANGES", outcome.resolution());
        assertTrue(outcome.warning(), outcome.warning().contains("база не менялась"));
        assertTrue("детали projectsAtRisk в тексте: " + outcome.warning(),
            outcome.warning().contains("Demo (есть правки, не залитые в базу)"));
        assertFalse(outcome.warning(), outcome.warning().contains("удалите вручную"));
    }

    /**
     * Fix round 6b / 7: быструю проверку сбросить не удалось, а запрошена полная замена. NO_CHANGES здесь — аномалия:
     * проект не заменён. Текст говорит это и называет причину; про оставшиеся расхождения — нейтрально; ни «база не
     * менялась», ни совета залить (deploy_project залил бы ту самую правку, которую просили отбросить).
     */
    @Test
    public void noChanges_recheckNotDone_dirtyProject_neutralWording() throws Exception {
        // Делегат без внутренних методов — сброс не выйдет.
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(true);
        ProjectFromInfobaseUpdater updater = updater(stateManager);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        assertEquals("NO_CHANGES", outcome.resolution());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("проект не заменён содержимым базы"));
        assertTrue(warning, warning.contains("в этой версии EDT нет внутреннего метода"));
        assertTrue(warning, warning.contains("EDT не заменила содержимое проекта: расхождения с базой остались в "
            + "проекте (Demo (есть правки, не залитые в базу))"));
        assertFalse(warning, warning.contains("база не менялась"));
        assertFalse(warning, warning.contains("deploy_project"));
        assertFalse("ConfigDumpInfo не удалялся — пара не «ни разу не синхронизирована»: " + warning,
            warning.contains("ни разу не синхронизированным"));
    }

    /**
     * После CHANGES_RESOLVED EDT содержимое проекта уже заменила, но может записать ConfigDumpInfo
     * позже, чем вернулся future, — «база не менялась… откатите» здесь ложь, которая толкает
     * удалить только что импортированное. Нужен нейтральный текст с деталями. L8: так — когда
     * проект отмечен синхронизированным, а EDT всё равно не считает его совпадающим с базой.
     */
    @Test
    public void changesResolved_markedButStillAtRisk_warnsToCheckProject_withoutClaimingInfobaseUnchanged()
            throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        // До обновления и при отметке состояние есть; после отметки сведений о синхронизации всё равно нет.
        when(stateManager.hasSynchronizationInfo(project, ref)).thenReturn(true, true, false);
        ProjectFromInfobaseUpdater updater = updater(stateManager);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_RESOLVED));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate).forceEdtSynchronization(ref, project);
        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        assertFalse(outcome.warning(), outcome.warning().contains("база не менялась"));
        assertTrue(outcome.warning(), outcome.warning().contains("проверьте проект"));
        assertTrue(outcome.warning(), outcome.warning().contains("Demo (проект ещё не синхронизировался"));
        assertFalse(outcome.warning(), outcome.warning().contains("удалите вручную"));
    }

    /**
     * L3: статус слияния WARNING (например, устаревший файл проекта не удалось удалить) — успех, но
     * его текст обязан дойти до вызывающего в {@code Outcome.warning}, вместе с прочими предупреждениями.
     */
    @Test
    public void pendingResolutionWithWarning_succeeds_andSurfacesMessageInOutcome() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        InfobaseSyncResolution pending = new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED);
        pending.setConflictResolution(CompletableFuture.completedFuture(new Status(IStatus.WARNING, "test",
            "не удалось удалить 1 устаревший файл проекта: src/CommonModules/Занятый/Module.bsl")));
        when(sync.retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any())).thenReturn(pending);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        assertTrue(String.valueOf(outcome.warning()),
            String.valueOf(outcome.warning()).contains("src/CommonModules/Занятый/Module.bsl"));
    }

    /** Как {@code MergingConflictResolver.postProcessMerge} EDT: CANCEL — тоже неуспех. */
    @Test
    public void pendingResolutionCancelled_fails() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        InfobaseSyncResolution pending = new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED);
        pending.setConflictResolution(CompletableFuture.completedFuture(
            new Status(IStatus.CANCEL, "test", "слияние прервано")));
        when(sync.retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any())).thenReturn(pending);

        expectFailure(updater, "CANCEL");
    }

    @Test
    public void pendingResolutionWithoutStatus_fails() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        InfobaseSyncResolution pending = new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED);
        pending.setConflictResolution(CompletableFuture.completedFuture(null));
        when(sync.retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any())).thenReturn(pending);

        expectFailure(updater, "без статуса");
    }

    @Test
    public void pendingResolutionFutureCancelled_isToolException() throws Exception {
        ProjectFromInfobaseUpdater updater = updater();
        InfobaseSyncResolution pending = new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED);
        CompletableFuture<IStatus> future = new CompletableFuture<>();
        future.cancel(true);
        pending.setConflictResolution(future);
        when(sync.retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any())).thenReturn(pending);

        expectFailure(updater, "EDT отменила обновление проекта Demo");
    }

    @Test
    public void successfulUpdate_projectClean_noWarning() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = mock(IInfobaseSynchronizationStateManager.class);
        when(stateManager.hasSynchronizationInfo(project, ref)).thenReturn(true);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(false);
        ProjectFromInfobaseUpdater updater = updater(stateManager);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_RESOLVED));

        // Fix round 8 (M3): без discardProjectChanges — инкрементальное обновление чистого проекта; с флагом оно
        // теперь честно предупреждает, что полной замены не было.
        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, false, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        assertNull(outcome.warning());
    }

    @Test
    public void v2Unavailable_refusesWithoutCallingEdt() {
        when(project.getName()).thenReturn("Demo");
        ProjectFromInfobaseUpdater updater = new ProjectFromInfobaseUpdater(sync, new SyncV2(() -> null),
            projects, models, ops, edtJobs, () -> resources);
        try {
            updater.update(project, ref, true, monitor);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("2026.1"));
        }
        verifyZeroInteractions(sync, models, resources, ops, edtJobs);
    }

    // ---- L8: после успешного обновления проект отмечается совпадающим с базой ----
    //
    // EDT записывает подтянутые из базы ресурсы с пустой подписью и считает проект «грязным» до
    // следующей заливки. Фикстура прогоняет настоящий обработчик (HeadlessInfobaseChangesResolver с
    // обёрткой ReplacingAssist) через мок EDT и воспроизводит это на настоящих файлах:
    // - слияние, как ядро EDT, копирует временный каталог в проект и делает проект «грязным»;
    // - forceEdtSynchronization делегата делает его снова чистым;
    // - модель EDT (effective) — это файлы на диске на момент последнего waitModelSynchronization;
    //   правка, которую модель ещё не импортировала (pendingEdit), видна isProjectDirty только после него.
    // Проект: Configuration (v1) и общий модуль «Нетронутый»; во временном каталоге — только Configuration
    // (v2). При полной перезагрузке «Нетронутый» — устаревший и удаляется, при инкрементальной — не трогается.

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Что вернёт {@code isProjectDirty}: слияние ставит {@code true}, отметка — {@code false}. */
    private final AtomicBoolean dirty = new AtomicBoolean();
    /** Правка, которую модель EDT ещё не импортировала: {@code isProjectDirty} увидит её после ожидания модели. */
    private final AtomicBoolean pendingEdit = new AtomicBoolean();
    /** Статус штатного слияния ядра. */
    private IStatus coreMergeStatus = Status.OK_STATUS;
    /** Изменения на стороне EDT, которые она передаёт обработчику ({@code collectEdtObjectChanges}). */
    private Set<String> edtRemovedFqns = Set.of();
    /** Что происходит в проекте, пока идёт слияние (например, правка пользователя). */
    private Runnable duringMerge = () -> {};
    private IInfobaseSynchronizationStateManager stateManager;
    /** SyncV2 фикстуры — шпион: тесты F4 проверяют вызов forceRecheck и его порядок. */
    private SyncV2 v2;
    private Path projectDir;
    private Path mergeDir;

    private ProjectFromInfobaseUpdater pulling(boolean fullReload, boolean dirtyBefore) throws Exception {
        dirty.set(dirtyBefore);
        stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.hasSynchronizationInfo(project, ref)).thenReturn(true);
        when(stateManager.isProjectDirty(project, ref)).thenAnswer(inv -> dirty.get());
        doAnswer(inv -> {
            dirty.set(false);
            return null;
        }).when(delegate).forceEdtSynchronization(ref, project);
        doAnswer(inv -> modelSynchronized()).when(models).waitModelSynchronization(project);

        projectDir = tmp.newFolder("project").toPath();
        mergeDir = tmp.newFolder("dt-mrg").toPath();
        write(projectDir, "src/Configuration/Configuration.mdo", "<mdclass:Configuration>v1");
        write(projectDir, "src/CommonModules/Нетронутый/Нетронутый.mdo", "<mdclass:CommonModule>");
        write(projectDir, "src/CommonModules/Нетронутый/Module.bsl", "// v1");
        write(mergeDir, "src/Configuration/Configuration.mdo", "<mdclass:Configuration>v2");
        modelReadsDisk();
        when(project.getLocation()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(projectDir.toString()));

        IUpdateProjectFlow updateFlow = mock(IUpdateProjectFlow.class);
        when(stateManager.startUpdateProjectFlow(dtProject, ref)).thenReturn(updateFlow);
        doAnswer(inv -> ((IInfobaseSynchronizationFlow.ComputableWithFlow<?, ?, ?>) inv.getArgument(0)).compute())
            .when(updateFlow).computeInFlowWithoutFinishing(any(), any());

        IInfobaseConfigurationChange change = mock(IInfobaseConfigurationChange.class);
        when(change.isEmpty()).thenReturn(false);
        when(change.isFullReloadRequired()).thenReturn(fullReload);
        IInfobaseUpdateConflictResolver.IConflictResolveAssist core =
            mock(IInfobaseUpdateConflictResolver.IConflictResolveAssist.class);
        when(core.mergeInfobaseChanges(any(), any(), any(), any(), anyBoolean())).thenAnswer(inv -> {
            // FileUtil.copyRecursively(temp, проект) ядра; во временном каталоге фикстуры содержимое — только src.
            copyTree(((Path) inv.getArgument(2)).resolve("src"), projectDir.resolve("src"));
            dirty.set(true); // как EDT: подтянутое записано с пустой подписью
            duringMerge.run();
            return CompletableFuture.completedFuture(coreMergeStatus);
        });
        // Как MergingConflictResolver: слияние синхронно, с флагом change.isFullReloadRequired().
        IInfobaseUpdateConflictResolver conflictResolver = mock(IInfobaseUpdateConflictResolver.class);
        when(conflictResolver.resolveConflict(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                IInfobaseUpdateConflictResolver.IConflictResolveAssist assist = inv.getArgument(6);
                return new InfobaseConflictResolution(
                    assist.mergeInfobaseChanges(project, Set.of(), mergeDir, Set.of(), fullReload));
            });
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor))).thenAnswer(inv -> {
            IInfobaseChangesResolver resolver = inv.getArgument(2);
            InfobaseConflictResolution resolution = resolver.resolveInfobaseChanges(project, ref, Set.of(), Set.of(),
                edtRemovedFqns, change, conflictResolver, core, null, monitor);
            InfobaseSyncResolution deferred =
                new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED);
            deferred.setConflictResolution(resolution.getConflictResolution());
            return deferred;
        });
        when(project.getName()).thenReturn("Demo");
        when(ref.getName()).thenReturn("DemoIB");
        IInfobaseSynchronizationStateManager sm = stateManager;
        v2 = spy(new SyncV2(() -> sm));
        return new ProjectFromInfobaseUpdater(sync, v2, projects, models, ops, edtJobs, () -> resourcesLookup.get());
    }

    /** Ответ {@code waitModelSynchronization}: модель импортировала отложенную правку и файлы с диска. */
    private Object modelSynchronized() {
        if (pendingEdit.getAndSet(false)) dirty.set(true);
        modelReadsDisk();
        return null;
    }

    /** Модель EDT обработала файлы проекта: подписи ресурсов — как на диске сейчас. */
    private void modelReadsDisk() {
        effective.clear();
        effective.putAll(ResourceStoreFakes.effective(projectDir));
    }

    private static void write(Path root, String relative, String content) {
        try {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void copyTree(Path from, Path to) throws java.io.IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Полная перезагрузка заменяет проект целиком — отметить можно, даже если до обновления в нём были
     * незалитые правки (их отбросили по discardProjectChanges). Сначала ресурсы и модель EDT, потом отметка:
     * записываются подписи, которые EDT видит в этот момент. Между снимками подписей изменились только
     * записанный слиянием {@code Configuration.mdo} и удалённый устаревший «Нетронутый» — это своё, отметке не мешает.
     */
    @Test
    public void fullReload_marksProjectSynchronized_afterRefreshAndModelSynchronization() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, true);
        Object configurationBefore = effective.get("src/Configuration/Configuration.mdo");

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        assertNull(outcome.warning());
        InOrder order = inOrder(project, models, delegate);
        order.verify(project, atLeastOnce()).refreshLocal(IResource.DEPTH_INFINITE, monitor);
        order.verify(models).waitModelSynchronization(project);
        order.verify(delegate).forceEdtSynchronization(ref, project);
        assertFalse("удаление устаревшего видно EDT", effective.containsKey("src/CommonModules/Нетронутый/Module.bsl"));
        assertFalse("запись слиянием видна EDT",
            configurationBefore.equals(effective.get("src/Configuration/Configuration.mdo")));
    }

    /**
     * Инкрементальное обновление проекта, который совпадал с базой, — тоже совпадение: отмечаем. Подпись
     * записанного слиянием {@code Configuration.mdo} между снимками изменилась — своё изменение, не чужое.
     */
    @Test
    public void incremental_cleanBefore_mergeTouchedKeyChanged_stillMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        Object configurationBefore = effective.get("src/Configuration/Configuration.mdo");

        // Fix round 8 (M3): без discardProjectChanges — инкрементальное обновление чистого проекта; с флагом оно
        // теперь честно предупреждает, что полной замены не было.
        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, false, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        assertNull(outcome.warning());
        verify(delegate).forceEdtSynchronization(ref, project);
        assertFalse(configurationBefore.equals(effective.get("src/Configuration/Configuration.mdo")));
    }

    /**
     * Инкрементальное обновление проекта, который EDT считала несинхронизированным: объекты, которых
     * обновление не коснулось, остались как были, — отметка спрятала бы их правки. Не отмечаем и говорим об этом.
     */
    @Test
    public void incremental_dirtyBefore_doesNotMark_andWarnsThatUntouchedObjectsKeptEdits() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, true);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("проект обновлён из базы, но EDT считала проект несинхронизированным ещё до "
            + "обновления: объекты, которых обновление не коснулось, остались как были — с незалитыми правками, если "
            + "они в них были (Demo (есть правки, не залитые в базу))"));
    }

    /**
     * Правка, которую модель EDT ещё не импортировала (EDT ждёт ~4 с после последнего изменения): быстрая
     * проверка {@code isProjectDirty} её не видит. Поэтому модель дожидаются ДО проверки «совпадал ли проект
     * с базой» и до первого снимка подписей — иначе инкрементальное обновление отметило бы правку
     * синхронизированной, и она бы не залилась никогда.
     */
    @Test
    public void editNotYetImportedByModel_isSeenBeforeCleanCheck_incrementalNotMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        pendingEdit.set(true);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        assertTrue(String.valueOf(outcome.warning()),
            String.valueOf(outcome.warning()).contains("EDT считала проект несинхронизированным ещё до обновления"));
        InOrder order = inOrder(models, resources, stateManager, sync);
        order.verify(models).waitModelSynchronization(project);
        order.verify(resources).getEffectiveResourceMetadata(dtProject);
        order.verify(stateManager).isProjectDirty(project, ref);
        order.verify(sync).retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor));
    }

    /**
     * Пока шло обновление, в проекте изменился объект, которого обновление не касалось (правка в IDE, запись
     * другим инструментом): отметка записала бы эту правку синхронизированной. Не отмечаем и называем ключ.
     */
    @Test
    public void untouchedObjectChangedDuringPull_notMarked_andNamed() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        duringMerge = () -> write(projectDir, "src/CommonModules/Нетронутый/Module.bsl", "// правка во время обновления");

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("во время обновления в проекте изменились объекты, которых обновление не "
            + "касалось: src/CommonModules/Нетронутый/Module.bsl — проект не отмечен синхронизированным"));
    }

    /** EDT передала обработчику изменения на своей стороне — проект не совпадал с базой: инкрементальное не отмечаем. */
    @Test
    public void edtSideChangesReportedByEdt_incrementalNotMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        edtRemovedFqns = Set.of("CommonModule.Удалённый");

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        assertTrue(String.valueOf(outcome.warning()),
            String.valueOf(outcome.warning()).contains("EDT считала проект несинхронизированным ещё до обновления"));
    }

    /**
     * Проверка «совпадал ли проект с базой» до обновления не удалась — считаем, что не совпадал, и говорим
     * именно это (M1, fix round 5), с причиной, а не «EDT считала проект несинхронизированным».
     */
    @Test
    public void cleanCheckFailedBefore_incrementalNotMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        when(stateManager.hasSynchronizationInfo(project, ref))
            .thenThrow(new IllegalStateException("состояние синхронизации читается")).thenReturn(true);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("проект обновлён из базы, но не удалось проверить, совпадал ли проект с "
            + "базой до обновления (состояние синхронизации читается): объекты, которых обновление не коснулось, "
            + "остались как были"));
        assertFalse(warning, warning.contains("EDT считала проект несинхронизированным"));
    }

    /** M5: не удалось дождаться модели только ДО обновления — инкрементальное не отмечаем, причину называем. */
    @Test
    public void preWaitFailedAlone_incrementalNotMarked_saysCouldNotVerify() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        doThrow(new IllegalStateException("модель ещё загружается")).doAnswer(inv -> modelSynchronized())
            .when(models).waitModelSynchronization(project);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("не удалось проверить, совпадал ли проект с базой до обновления"));
        assertTrue(warning, warning.contains("модель ещё загружается"));
    }

    // ---- O1 (fix round 5): окончательная проверка — в задании, после ожидания модели EDT ----

    /**
     * write_module только что записал файл, модель EDT его ещё не импортировала: синхронная проверка сказала
     * «чисто». С {@code discardProjectChanges = false} обновление проверяет проект заново после ожидания модели
     * и отказывает ДО {@code retrieveInfobaseChanges} — правка не перезаписана.
     */
    @Test
    public void discardFalse_editSeenOnlyAfterModelWait_refusesBeforeRetrieve() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        pendingEdit.set(true);

        try {
            updater.update(project, ref, false, monitor);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Demo (есть правки, не залитые в базу)"));
            assertTrue(e.getMessage(), e.getMessage().contains("discardProjectChanges"));
        }
        verify(sync, never()).retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any());
        InOrder order = inOrder(models, stateManager);
        order.verify(models).waitModelSynchronization(project);
        order.verify(stateManager).isProjectDirty(project, ref);
    }

    /**
     * Fix round 6b: отказ O1 — {@link ProjectsAtRiskException} с пунктами: сценарий, уже загрузивший в базу .dt или
     * .cfe, заменит общий текст (с советом deploy_project) своим. Сам текст по умолчанию — общий.
     */
    @Test
    public void discardFalse_refusal_carriesAtRiskItems() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        pendingEdit.set(true);

        try {
            updater.update(project, ref, false, monitor);
            fail("expected ProjectsAtRiskException");
        } catch (ToolException e) {
            assertTrue(e.getClass().getName(), e instanceof ProjectsAtRiskException);
            assertEquals(java.util.List.of("Demo (есть правки, не залитые в базу)"),
                ((ProjectsAtRiskException) e).atRisk());
            assertEquals(SyncV2.atRiskMessage(java.util.List.of("Demo (есть правки, не залитые в базу)")),
                e.getMessage());
        }
        verify(sync, never()).retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any());
        verify(ops, never()).releaseDesignerSession(any(), any(), any());
    }

    /** Модели не дождались — совпадение не проверить: без discardProjectChanges отказ до обновления (fail-closed). */
    @Test
    public void discardFalse_modelWaitFailed_refusesBeforeRetrieve() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        doThrow(new IllegalStateException("модель ещё загружается")).when(models).waitModelSynchronization(project);

        try {
            updater.update(project, ref, false, monitor);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("модель ещё загружается"));
            assertTrue(e.getMessage(), e.getMessage().contains("discardProjectChanges"));
        }
        verify(sync, never()).retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any());
    }

    /** Чистый проект без discardProjectChanges — проверка проходит, обновление и отметка как обычно. */
    @Test
    public void discardFalse_cleanProject_proceedsAndMarks() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, false, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        assertNull(outcome.warning());
        verify(delegate).forceEdtSynchronization(ref, project);
    }

    /**
     * Помощник инструментов (шаг {@code check-projects}): для каждого проекта — ожидание его модели, затем проверка;
     * модели не дождались — пункт «не удалось дождаться» (fail-closed).
     */
    @Test
    public void projectsAtRiskAfterModelSync_waitsForEachModelThenChecks() throws Exception {
        IInfobaseSynchronizationStateManager sm = SyncV2Fakes.stateManager(delegate);
        IProject clean = mock(IProject.class);
        IProject edited = mock(IProject.class);
        IProject stuck = mock(IProject.class);
        when(clean.getName()).thenReturn("Чистый");
        when(edited.getName()).thenReturn("Правленый");
        when(stuck.getName()).thenReturn("Застрявший");
        AtomicBoolean editImported = new AtomicBoolean();
        when(sm.isProjectDirty(edited, ref)).thenAnswer(inv -> editImported.get());
        doAnswer(inv -> {
            editImported.set(true);
            return null;
        }).when(models).waitModelSynchronization(edited);
        doThrow(new IllegalStateException("модель выгружена")).when(models).waitModelSynchronization(stuck);
        ProjectFromInfobaseUpdater updater = updater(sm);

        java.util.List<String> atRisk = updater.projectsAtRiskAfterModelSync(java.util.List.of(clean, edited, stuck), ref);

        assertEquals(2, atRisk.size());
        assertEquals("Правленый (есть правки, не залитые в базу)", atRisk.get(0));
        assertTrue(atRisk.get(1), atRisk.get(1).startsWith("Застрявший (не удалось дождаться"));
        assertTrue(atRisk.get(1), atRisk.get(1).contains("модель выгружена"));
        InOrder order = inOrder(models, sm);
        order.verify(models).waitModelSynchronization(edited);
        order.verify(sm).isProjectDirty(edited, ref);
    }

    // ---- F4 (fix round 6): быстрая проверка EDT по идентификатору поколения — сбрасывается перед каждым обновлением ----

    /**
     * EDT могла ответить NO_CHANGES, не сравнивая конфигурацию (идентификатор поколения данных базы не изменился, а
     * загрузка .dt/.cfe его не меняет). Поэтому перед каждым забором — {@code forceRecheck} с флагом вызова: после
     * проверки проекта (O1) и до {@code retrieveInfobaseChanges}.
     */
    @Test
    public void forceRecheck_withDiscardFlag_afterProjectCheck_beforeRetrieve() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);

        updater.update(project, ref, false, monitor);

        InOrder order = inOrder(stateManager, v2, sync);
        order.verify(stateManager).isProjectDirty(project, ref);
        order.verify(v2).forceRecheck(project, ref, false);
        order.verify(sync).retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor));
    }

    /** discardProjectChanges = true — полная замена: сброс с fullReload (EDT выгрузит конфигурацию целиком). */
    @Test
    public void discardTrue_forcesFullReloadRecheck() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, true);

        updater.update(project, ref, true, monitor);

        verify(v2).forceRecheck(project, ref, true);
    }

    /**
     * NO_CHANGES, а быструю проверку сбросить не удалось: EDT могла ответить так, не сравнив конфигурацию, —
     * предупреждение с причиной: если базу меняли в обход EDT, проект может с ней не совпадать.
     */
    @Test
    public void noChanges_recheckNotDone_warnsWithReason() throws Exception {
        // Делегат без внутренних методов — сброс не выйдет («нет внутреннего метода»).
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(false);
        ProjectFromInfobaseUpdater updater = updater(stateManager);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, false, monitor);

        assertEquals("NO_CHANGES", outcome.resolution());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("быстрой проверке"));
        assertTrue(warning, warning.contains("в этой версии EDT нет внутреннего метода"));
        assertTrue(warning, warning.contains("проверьте проект"));
        assertFalse(warning, warning.contains("deploy_project"));
    }

    /** NO_CHANGES после сброса быстрой проверки — это честное сравнение: лишнего предупреждения нет. */
    @Test
    public void noChanges_recheckDone_noExtraWarning() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(false);
        SyncV2 spied = spy(new SyncV2(() -> stateManager));
        doReturn(new SyncV2.Recheck(true, null)).when(spied).forceRecheck(any(), any(), anyBoolean());
        ProjectFromInfobaseUpdater updater = updaterWith(spied);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, false, monitor);

        assertEquals("NO_CHANGES", outcome.resolution());
        assertNull(outcome.warning());
    }

    // ---- Fix round 7: «шлюз» EDT — сеанс агента конфигуратора закрывается перед каждым забором ----
    //
    // DesignerSessionInfobaseConnection отвечает NO_CHANGES, не спрашивая базу, пока externalChangesCheckRequired =
    // false (после каждого забора); true его делает только закрытие сеанса агента конфигуратора (слушатель closed()).

    /** Порядок: проверка проекта (O1) → закрытие сеанса агента → сброс быстрой проверки → забор. */
    @Test
    public void releasesAgentSession_afterCheck_beforeRecheckAndRetrieve() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);

        updater.update(project, ref, false, monitor);

        InOrder order = inOrder(stateManager, ops, v2, sync);
        order.verify(stateManager).isProjectDirty(project, ref);
        order.verify(ops).releaseDesignerSession(project, ref, monitor);
        order.verify(v2).forceRecheck(project, ref, false);
        order.verify(sync).retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor));
    }

    /**
     * Проект расширения: исполнитель и сеанс агента — по родительской конфигурации, как у операций сценариев 2 и 3
     * (загрузка и применение .cfe): база одна, установку EDT выбирает для пары «конфигурация — база».
     */
    @Test
    public void extensionProject_releasesAgentSessionThroughParentConfiguration() throws Exception {
        IProject parent = mock(IProject.class);
        IExtensionProject extension = mock(IExtensionProject.class);
        when(extension.getParentProject()).thenReturn(parent);
        when(extension.getDtProject()).thenReturn(dtProject);
        when(projects.getProject(project)).thenReturn(extension);
        ProjectFromInfobaseUpdater updater = updater();
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        updater.update(project, ref, false, monitor);

        verify(ops).releaseDesignerSession(parent, ref, monitor);
        verify(ops, never()).releaseDesignerSession(eq(project), any(), any());
    }

    /**
     * Сеанс закрыть не удалось — шлюз мог остаться закрытым, и забор может ответить NO_CHANGES, вовсе не спросив
     * базу: записанный ConfigDumpInfo НЕ удаляем даже при полной замене (иначе пара осталась бы «ни разу не
     * синхронизированной», а проект — прежним). NO_CHANGES — аномалия с причиной, без совета залить.
     */
    @Test
    public void releaseFailure_discard_keepsDumpInfo_andNoChangesIsAnomalyWithReason() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(true);
        SyncV2 rechecked = spy(new SyncV2(() -> stateManager));
        doReturn(new SyncV2.Recheck(true, null)).when(rechecked).forceRecheck(any(), any(), anyBoolean());
        ProjectFromInfobaseUpdater updater = updaterWith(rechecked);
        when(ops.releaseDesignerSession(project, ref, monitor)).thenReturn("агент конфигуратора не отвечает");
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(rechecked).forceRecheck(project, ref, false);
        verify(rechecked, never()).forceRecheck(project, ref, true);
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("проект не заменён содержимым базы"));
        assertTrue(warning, warning.contains("агент конфигуратора не отвечает"));
        assertFalse(warning, warning.contains("deploy_project"));
        assertFalse(warning, warning.contains("база не менялась"));
        assertFalse(warning, warning.contains("ни разу не синхронизированным"));
    }

    /** Перенос ConfigDumpInfo для проб: путей на диске тесты обновления не трогают. */
    private static final SyncV2.DumpInfoMove MOVED = new SyncV2.DumpInfoMove(Path.of("ConfigDumpInfo.xml"),
        Path.of("ConfigDumpInfo.xml.mcp-prev"));

    /**
     * Полная замена: сеанс закрыт, быстрая проверка сброшена, ConfigDumpInfo отложен — а EDT всё равно ответила
     * NO_CHANGES, и вернуть отложенное не удалось. Аномалия: проект не заменён; EDT теперь считает пару ни разу не
     * синхронизированной — выход: перезапуск EDT и повтор с флагом (fix round 9: только когда вернуть не удалось).
     * Ни «база не менялась», ни совета залить ту правку, которую просили отбросить.
     */
    @Test
    public void discard_noChanges_dumpRestoreFailed_saysPairUnsynced_restartAndRepeat() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(true);
        SyncV2 rechecked = spy(new SyncV2(() -> stateManager));
        doReturn(new SyncV2.Recheck(true, null, MOVED)).when(rechecked).forceRecheck(any(), any(), anyBoolean());
        doReturn(new SyncV2.DumpInfoSettlement(SyncV2.DumpInfoFate.RESTORE_FAILED, "файл занят"))
            .when(rechecked).settleDumpInfo(any(), any(), any(), anyBoolean());
        ProjectFromInfobaseUpdater updater = updaterWith(rechecked);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(rechecked).forceRecheck(project, ref, true);
        verify(rechecked).settleDumpInfo(eq(project), eq(ref), any(), eq(false));
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("файл занят"));
        assertTrue(warning, warning.contains("проект не заменён содержимым базы"));
        assertTrue(warning, warning.contains("ни разу не синхронизированным"));
        assertTrue(warning, warning.contains("повторите update_project_from_infobase с discardProjectChanges: true"));
        assertFalse(warning, warning.contains("deploy_project"));
        assertFalse(warning, warning.contains("база не менялась"));
        // Fix round 8 (M1): «закрытие без ошибки» — не доказательство, что шлюз открылся; выход — перезапуск EDT.
        assertTrue(warning, warning.contains("закрытие сеанса агента конфигуратора прошло без ошибки"));
        assertFalse(warning, warning.contains("сеанс агента конфигуратора закрыт,"));
        assertTrue(warning, warning.contains("перезапустите EDT"));
    }

    /**
     * Fix round 9 (рекомендация 1): ConfigDumpInfo отложен, EDT ответила NO_CHANGES — отложенное возвращено: проект
     * не заменён, записанное состояние синхронизации прежнее; перезапуск EDT не нужен.
     */
    @Test
    public void discard_noChanges_dumpRestored_saysStateUnchanged_noRestart() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(true);
        SyncV2 rechecked = spy(new SyncV2(() -> stateManager));
        doReturn(new SyncV2.Recheck(true, null, MOVED)).when(rechecked).forceRecheck(any(), any(), anyBoolean());
        doReturn(new SyncV2.DumpInfoSettlement(SyncV2.DumpInfoFate.RESTORED, null))
            .when(rechecked).settleDumpInfo(any(), any(), any(), anyBoolean());
        ProjectFromInfobaseUpdater updater = updaterWith(rechecked);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        String warning = String.valueOf(updater.update(project, ref, true, monitor).warning());

        assertTrue(warning, warning.contains("проект не заменён содержимым базы"));
        assertTrue(warning, warning.contains("записанное состояние синхронизации проекта с базой прежнее"));
        assertFalse(warning, warning.contains("ни разу не синхронизированным"));
        assertFalse(warning, warning.contains("перезапустите EDT"));
        assertFalse(warning, warning.contains("deploy_project"));
    }

    /**
     * Fix round 8 (M2) / 9: ConfigDumpInfo отложен, а сброс всё же «не сделан» (не снялся замок) — сведения о переносе
     * доходят, отложенное возвращается; текст причин не противоречит тексту самого сброса («…сброшена, но…»).
     */
    @Test
    public void discard_dumpMovedButRecheckNotDone_restoredAndReasonKept() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(true);
        SyncV2 rechecked = spy(new SyncV2(() -> stateManager));
        doReturn(new SyncV2.Recheck(false, "не удалось снять замок состояния синхронизации базы: занято", MOVED))
            .when(rechecked).forceRecheck(any(), any(), anyBoolean());
        doReturn(new SyncV2.DumpInfoSettlement(SyncV2.DumpInfoFate.RESTORED, null))
            .when(rechecked).settleDumpInfo(any(), any(), any(), anyBoolean());
        ProjectFromInfobaseUpdater updater = updaterWith(rechecked);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        String warning = String.valueOf(updater.update(project, ref, true, monitor).warning());

        verify(rechecked).settleDumpInfo(eq(project), eq(ref), any(), eq(false));
        assertTrue(warning, warning.contains("не удалось снять замок состояния синхронизации базы: занято"));
        assertTrue(warning, warning.contains("прежнее"));
        assertFalse("формулировка причин не утверждает «не удалось сбросить»: " + warning,
            warning.contains("не удалось сбросить быструю проверку"));
    }

    /** Fix round 9 (рекомендация 1): забор упал — отложенный ConfigDumpInfo возвращается, ошибка — как была. */
    @Test
    public void retrieveFails_movedDumpInfoIsSettledWithoutFullReload() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(true);
        SyncV2 rechecked = spy(new SyncV2(() -> stateManager));
        doReturn(new SyncV2.Recheck(true, null, MOVED)).when(rechecked).forceRecheck(any(), any(), anyBoolean());
        doReturn(new SyncV2.DumpInfoSettlement(SyncV2.DumpInfoFate.RESTORED, null))
            .when(rechecked).settleDumpInfo(any(), any(), any(), anyBoolean());
        ProjectFromInfobaseUpdater updater = updaterWith(rechecked);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor))).thenThrow(
            new InfobaseSynchronizationException(Status.error("Ошибка получения списка изменений в информационной базе")));

        try {
            updater.update(project, ref, true, monitor);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Ошибка получения списка изменений"));
        }
        verify(rechecked).settleDumpInfo(eq(project), eq(ref), any(), eq(false));
    }

    /** Fix round 9 (рекомендация 1): полная перезагрузка прошла — отложенная копия выбрасывается. */
    @Test
    public void fullReloadDone_movedDumpInfoIsSettledAsDiscarded() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, true);
        doReturn(new SyncV2.Recheck(true, null, MOVED)).when(v2).forceRecheck(any(), any(), anyBoolean());
        doReturn(new SyncV2.DumpInfoSettlement(SyncV2.DumpInfoFate.DISCARDED, null))
            .when(v2).settleDumpInfo(any(), any(), any(), anyBoolean());

        assertEquals("CHANGES_RESOLVED", updater.update(project, ref, true, monitor).resolution());

        verify(v2).settleDumpInfo(eq(project), eq(ref), any(), eq(true));
    }

    /**
     * Fix round 9 (m1): между первой проверкой проекта и забором — ожидания и закрытие сеанса (минуты): без
     * discardProjectChanges проект проверяется ещё раз (с ожиданием модели и новым снимком) прямо перед забором.
     */
    @Test
    public void discardFalse_projectRecheckedRightBeforeRetrieve() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);

        updater.update(project, ref, false, monitor);

        InOrder order = inOrder(v2, models, stateManager, sync);
        order.verify(v2).forceRecheck(project, ref, false);
        order.verify(models).waitModelSynchronization(project);
        order.verify(stateManager).isProjectDirty(project, ref);
        order.verify(sync).retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor));
    }

    /** Fix round 9 (m1): правка появилась, пока закрывался сеанс агента, — отказ до забора, как у первой проверки. */
    @Test
    public void discardFalse_editDuringRelease_refusedBeforeRetrieve() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        doAnswer(inv -> {
            pendingEdit.set(true);
            return null;
        }).when(ops).releaseDesignerSession(any(), any(), any());

        try {
            updater.update(project, ref, false, monitor);
            fail("expected ProjectsAtRiskException");
        } catch (ProjectsAtRiskException e) {
            assertEquals(java.util.List.of("Demo (есть правки, не залитые в базу)"), e.atRisk());
        }
        verify(sync, never()).retrieveInfobaseChanges(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * Fix round 9 (m4): спросить EDT, идёт ли синхронизация базы, не удалось — fail-closed: сеанс агента не
     * закрываем, сброс не делаем; NO_CHANGES несёт причину.
     */
    @Test
    public void flowCheckFails_noRelease_noRecheck_reasonInWarning() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(false);
        when(stateManager.isFlowActive(ref)).thenThrow(new IllegalStateException("состояние базы не читается"));
        SyncV2 flaky = spy(new SyncV2(() -> stateManager));
        ProjectFromInfobaseUpdater updater = updaterWith(flaky);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(ops, never()).releaseDesignerSession(any(), any(), any());
        verify(flaky, never()).forceRecheck(any(), any(), anyBoolean());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("не удалось проверить, идёт ли синхронизация базы"));
        assertTrue(warning, warning.contains("состояние базы не читается"));
    }

    /** Одна и та же причина (чужая синхронизация дольше предела) у сеанса и у сброса — называется один раз. */
    @Test
    public void sameReasonForGateAndRecheck_namedOnce() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(false);
        SyncV2 busy = spy(new SyncV2(() -> stateManager));
        doReturn("EDT синхронизирует эту базу дольше 120 с").when(busy).awaitNoActiveFlow(any(), any(), any());
        ProjectFromInfobaseUpdater updater = updaterWith(busy);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        String warning = String.valueOf(updater.update(project, ref, false, monitor).warning());

        assertTrue(warning, warning.contains("EDT синхронизирует эту базу дольше 120 с"));
        assertEquals(warning, warning.indexOf("EDT синхронизирует эту базу дольше 120 с"),
            warning.lastIndexOf("EDT синхронизирует эту базу дольше 120 с"));
    }

    /**
     * Fix round 8 (M3): полная замена запрошена, но сеанс агента не закрыт — EDT забрала изменения инкрементально:
     * предупреждение, что полной замены не было, только изменённое в базе, с причиной; без совета залить.
     */
    @Test
    public void discard_releaseFailed_incrementalPull_warnsFullReplacementNotDone() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        when(ops.releaseDesignerSession(project, ref, monitor)).thenReturn("агент конфигуратора не отвечает");

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("полная замена не выполнена"));
        assertTrue(warning, warning.contains("агент конфигуратора не отвечает"));
        assertTrue(warning, warning.contains("только объекты, изменённые в базе"));
        assertFalse(warning, warning.contains("deploy_project"));
    }

    // ---- Fix round 8 (L9): фоновые проверки EDT и чужая синхронизация — ждём, а не сталкиваемся ----

    /**
     * Порядок: проверки EDT → модель и проверка проекта → нет ли чужой синхронизации → закрытие сеанса → снова →
     * сброс → забор → … → снова → отметка.
     */
    @Test
    public void order_edtChecks_model_check_flow_release_flow_recheck_retrieve_flow_mark() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);

        updater.update(project, ref, false, monitor);

        InOrder order = inOrder(edtJobs, models, stateManager, v2, ops, sync, delegate);
        order.verify(edtJobs).await(monitor);
        order.verify(models).waitModelSynchronization(project);
        order.verify(stateManager).isProjectDirty(project, ref);
        order.verify(v2).awaitNoActiveFlow(eq(ref), eq(monitor), any());
        order.verify(ops).releaseDesignerSession(project, ref, monitor);
        order.verify(v2).awaitNoActiveFlow(eq(ref), eq(monitor), any());
        order.verify(v2).forceRecheck(project, ref, false);
        order.verify(sync).retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor));
        order.verify(v2).awaitNoActiveFlow(eq(ref), eq(monitor), any());
        order.verify(delegate).forceEdtSynchronization(ref, project);
    }

    /** Проверки EDT идут дольше предела — отказ до всего: ни модели, ни сеанса агента, ни забора. */
    @Test
    public void edtChecksLongerThanBound_refusesBeforeTouchingAnything() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);
        when(edtJobs.await(monitor)).thenReturn("EDT ещё проверяет состояние синхронизации проектов (проба)");

        try {
            updater.update(project, ref, true, monitor);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("EDT ещё проверяет состояние синхронизации проектов"));
            assertTrue(e.getMessage(), e.getMessage().contains("повторите update_project_from_infobase позже"));
        }
        verifyZeroInteractions(models, ops, sync);
        verify(v2, never()).forceRecheck(any(), any(), anyBoolean());
    }

    /**
     * Чужая синхронизация базы не кончилась за предел — сеанс агента под ней не закрываем и сброс не делаем; итог
     * NO_CHANGES несёт причину.
     */
    @Test
    public void flowStillActiveAtBound_noRelease_noRecheck_warnsWithReason() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(false);
        SyncV2 busy = spy(new SyncV2(() -> stateManager));
        doReturn("EDT синхронизирует эту базу дольше 120 с").when(busy).awaitNoActiveFlow(any(), any(), any());
        ProjectFromInfobaseUpdater updater = updaterWith(busy);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(ops, never()).releaseDesignerSession(any(), any(), any());
        verify(busy, never()).forceRecheck(any(), any(), anyBoolean());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("EDT синхронизирует эту базу дольше 120 с"));
        assertTrue(warning, warning.contains("проект не заменён содержимым базы"));
        assertFalse(warning, warning.contains("ни разу не синхронизированным"));
    }

    /** Перед отметкой чужая синхронизация не кончилась за предел — не отмечаем, причина в предупреждении. */
    @Test
    public void flowActiveBeforeMark_notMarked_withReason() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);
        doReturn(null).doReturn(null).doReturn("EDT синхронизирует эту базу дольше 120 с")
            .when(v2).awaitNoActiveFlow(any(), any(), any());

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, false, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("не удалось отметить проект синхронизированным"));
        assertTrue(warning, warning.contains("EDT синхронизирует эту базу дольше 120 с"));
    }

    /**
     * Без discardProjectChanges: сеанс закрыть не удалось, EDT ответила NO_CHANGES — ответ мог прийти без
     * сравнения; предупреждение называет причину (сеанс), без совета залить.
     */
    @Test
    public void noDiscard_releaseFailure_noChanges_warnsWithSessionReason() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(false);
        SyncV2 rechecked = spy(new SyncV2(() -> stateManager));
        doReturn(new SyncV2.Recheck(true, null)).when(rechecked).forceRecheck(any(), any(), anyBoolean());
        ProjectFromInfobaseUpdater updater = updaterWith(rechecked);
        when(ops.releaseDesignerSession(project, ref, monitor)).thenReturn("агент конфигуратора не отвечает");
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, false, monitor);

        assertEquals("NO_CHANGES", outcome.resolution());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("быстрой проверке"));
        assertTrue(warning, warning.contains("сеанс агента конфигуратора EDT"));
        assertTrue(warning, warning.contains("агент конфигуратора не отвечает"));
        assertTrue(warning, warning.contains("проверьте проект"));
        assertFalse(warning, warning.contains("deploy_project"));
    }

    // ---- M5 (fix round 5) ----

    /**
     * Переименование только регистром: до обновления EDT хранила ключ {@code товары/товары.mdo}, слияние записало
     * {@code Товары/Товары.mdo}. Для NTFS это один файл — своё изменение, а не чужая правка: отмечаем.
     */
    @Test
    public void caseOnlyRename_isOwnChange_stillMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(false, false);
        write(mergeDir, "src/Catalogs/Товары/Товары.mdo", "<mdclass:Catalog>v2");
        EdtResourceMetadata old = new EdtResourceMetadata(new byte[] {1}, java.util.UUID.randomUUID());
        EdtResourceMetadata renamed = new EdtResourceMetadata(new byte[] {2}, old.getUuid());
        doReturn(new HashMap<>(Map.of("src/Catalogs/товары/товары.mdo", old)))
            .doReturn(new HashMap<>(Map.of("src/Catalogs/Товары/Товары.mdo", renamed)))
            .when(resources).getEffectiveResourceMetadata(dtProject);

        // Fix round 8 (M3): без discardProjectChanges — инкрементальное обновление чистого проекта; с флагом оно
        // теперь честно предупреждает, что полной замены не было.
        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, false, monitor);

        assertNull(outcome.warning());
        verify(delegate).forceEdtSynchronization(ref, project);
    }

    /** Снимок подписей не снялся только ПОСЛЕ обновления — чужие правки не исключить: не отмечаем. */
    @Test
    public void afterSnapshotFailsAlone_notMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);
        doAnswer(inv -> new HashMap<>(effective))
            .doThrow(new IllegalStateException("хранилище подписей закрылось"))
            .when(resources).getEffectiveResourceMetadata(dtProject);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("не удалось снять подписи ресурсов проекта после обновления"));
        assertTrue(warning, warning.contains("хранилище подписей закрылось"));
    }

    /** NO_CHANGES: EDT содержимое проекта не трогала — отмечать нечего; модель ждём только до проверки. */
    @Test
    public void noChanges_neverMarks() throws Exception {
        IInfobaseSynchronizationStateManager stateManager = SyncV2Fakes.stateManager(delegate);
        when(stateManager.hasSynchronizationInfo(project, ref)).thenReturn(true);
        when(stateManager.isProjectDirty(project, ref)).thenReturn(false);
        ProjectFromInfobaseUpdater updater = updater(stateManager);
        when(sync.retrieveInfobaseChanges(eq(project), eq(ref), any(), anyBoolean(), eq(monitor)))
            .thenReturn(new InfobaseSyncResolution(InfobaseChangesResolutionResult.NO_CHANGES));

        assertEquals("NO_CHANGES", updater.update(project, ref, true, monitor).resolution());
        verify(models).waitModelSynchronization(project);
        verifyZeroInteractions(delegate);
    }

    /** Отметить не удалось (внутренний API EDT) — предупреждение с причиной; обновление при этом успешно. */
    @Test
    public void markFailure_warnsWithReason() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);
        doThrow(new IllegalStateException("хранилище состояния синхронизации занято"))
            .when(delegate).forceEdtSynchronization(ref, project);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("Demo (есть правки, не залитые в базу)"));
        assertTrue(warning, warning.contains("EDT считает подтянутые объекты несинхронизированными — так EDT ведёт "
            + "себя и в IDE; не удалось отметить проект синхронизированным: "));
        assertTrue(warning, warning.contains("хранилище состояния синхронизации занято"));
        // Fix round 8: ни на одном пути обновления — ни совета, ни упоминания deploy_project (после загрузки и при
        // полной замене заливка перезаписала бы базу проектом).
        assertFalse(warning, warning.contains("deploy_project"));
    }

    /** Модель EDT не дождались — подписи могли быть неактуальны: не отмечаем, причина — в предупреждении. */
    @Test
    public void modelSynchronizationFailure_skipsMark_andWarnsWithReason() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);
        doThrow(new IllegalStateException("модель проекта Demo выгружена")).when(models).waitModelSynchronization(project);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("не удалось отметить проект синхронизированным"));
        assertTrue(warning, warning.contains("модель проекта Demo выгружена"));
    }

    /**
     * Слияние завершилось с предупреждением (например, устаревший файл не удалился) — содержимое
     * проекта может не совпадать с базой, и отметка это спрятала бы: не отмечаем.
     */
    @Test
    public void pullWithWarning_isNotMarked() throws Exception {
        coreMergeStatus = new Status(IStatus.WARNING, "test", "часть файлов не скопирована");
        ProjectFromInfobaseUpdater updater = pulling(true, false);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("часть файлов не скопирована"));
        assertTrue(warning, warning.contains("проект не отмечен синхронизированным"));
        assertTrue(warning, warning.contains("проверьте проект"));
        assertFalse(warning, warning.contains("deploy_project"));
    }

    /** Обновление ресурсов проекта после синхронизации не удалось — модель может не знать о файлах: не отмечаем. */
    @Test
    public void refreshFailure_isNotMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);
        // Первый refreshLocal — обёртки слияния, второй — самого обновления.
        doNothing().doThrow(new CoreException(Status.error("ресурсы проекта заблокированы")))
            .when(project).refreshLocal(IResource.DEPTH_INFINITE, monitor);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("ресурсы проекта заблокированы"));
        assertTrue(warning, warning.contains("проект не отмечен синхронизированным"));
        assertTrue(warning, warning.contains("проверьте проект"));
        assertFalse(warning, warning.contains("deploy_project"));
    }

    /**
     * Состояния синхронизации после обновления нет (проект расширения, у родителя которого нет состояния):
     * делегат EDT записал бы общий статический {@code InfobaseSyncState.UNDEFINED} — не отмечаем.
     */
    @Test
    public void noSyncInfoAfterPull_notMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);
        when(stateManager.hasSynchronizationInfo(project, ref)).thenReturn(true, false);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("не удалось отметить проект синхронизированным: EDT не записала состояние "
            + "синхронизации"));
    }

    /**
     * Идёт другая синхронизация с этой базой — её замок трогать нельзя: не отмечаем. Проверяется собственная защита
     * отметки (синхронизация началась уже после нашего ожидания, fix round 8): ожидание здесь ответило «можно».
     */
    @Test
    public void anotherFlowActive_notMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);
        when(stateManager.isFlowActive(ref)).thenReturn(true);
        doReturn(null).when(v2).awaitNoActiveFlow(any(), any(), any());

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        assertTrue(String.valueOf(outcome.warning()),
            String.valueOf(outcome.warning()).contains("другая синхронизация с этой базой идёт прямо сейчас"));
    }

    /** Снимок подписей ресурсов не снять — чужие изменения не исключить: не отмечаем (fail-closed). */
    @Test
    public void resourceSnapshotUnavailable_notMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);
        doThrow(new IllegalStateException("хранилище подписей ресурсов закрыто"))
            .when(resources).getEffectiveResourceMetadata(dtProject);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("не удалось отметить проект синхронизированным"));
        assertTrue(warning, warning.contains("хранилище подписей ресурсов закрыто"));
    }

    /**
     * Службы подписей ресурсов EDT нет (на ветке, где она не зарегистрирована, {@code ServiceAccess} бросает):
     * снимков нет — чужие правки не исключить, не отмечаем (fail-closed). Само обновление при этом успешно.
     */
    @Test
    public void resourceServiceUnavailable_notMarked_pullStillSucceeds() throws Exception {
        resourcesLookup = () -> {
            throw new IllegalStateException("служба IResourceStoreManager не зарегистрирована");
        };
        ProjectFromInfobaseUpdater updater = pulling(true, false);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        verify(delegate, never()).forceEdtSynchronization(any(), any());
        String warning = String.valueOf(outcome.warning());
        assertTrue(warning, warning.contains("не удалось отметить проект синхронизированным"));
        assertTrue(warning, warning.contains("сервис ресурсов EDT недоступен"));
        assertTrue(warning, warning.contains("служба IResourceStoreManager не зарегистрирована"));
    }

    /** Класса службы нет вовсе (старая ветка EDT): {@code LinkageError} из поиска — то же, без исключения наружу. */
    @Test
    public void resourceServiceClassAbsent_notMarked_pullStillSucceeds() throws Exception {
        resourcesLookup = () -> {
            throw new NoClassDefFoundError("com/_1c/g5/v8/dt/core/resource/IResourceStoreManager");
        };
        ProjectFromInfobaseUpdater updater = pulling(true, false);

        ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

        assertEquals("CHANGES_RESOLVED", outcome.resolution());
        verify(delegate, never()).forceEdtSynchronization(any(), any());
        assertTrue(String.valueOf(outcome.warning()),
            String.valueOf(outcome.warning()).contains("сервис ресурсов EDT недоступен"));
    }

    /**
     * Не узнать, что записало слияние (временный каталог не обойти: точка соединения NTFS, цель которой удалена), —
     * своё от чужого не отличить: не отмечаем (fail-closed).
     */
    @Test
    public void mergedKeysUnknown_notMarked() throws Exception {
        ProjectFromInfobaseUpdater updater = pulling(true, false);
        Path gone = tmp.newFolder("link-target-gone").toPath();
        Path junction = mergeDir.resolve("dangling-link");
        linkOrSkip(junction, gone);
        Files.delete(gone);
        try {
            ProjectFromInfobaseUpdater.Outcome outcome = updater.update(project, ref, true, monitor);

            verify(delegate, never()).forceEdtSynchronization(any(), any());
            String warning = String.valueOf(outcome.warning());
            assertTrue(warning, warning.contains("не удалось отметить проект синхронизированным: не удалось узнать, какие "
                + "файлы записало обновление"));
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    /** Точка соединения (Windows, {@code mklink /J}) или символьная ссылка; ОС не дала создать — тест пропускается. */
    private static void linkOrSkip(Path link, Path target) throws Exception {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            org.junit.Assume.assumeTrue("mklink /J не сработал", p.waitFor() == 0 && Files.exists(link,
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
        } else {
            try {
                Files.createSymbolicLink(link, target);
            } catch (UnsupportedOperationException | java.io.IOException e) {
                org.junit.Assume.assumeNoException(e);
            }
        }
    }

    private void expectFailure(ProjectFromInfobaseUpdater updater, String fragment) {
        try {
            updater.update(project, ref, true, monitor);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(fragment));
        }
    }
}
