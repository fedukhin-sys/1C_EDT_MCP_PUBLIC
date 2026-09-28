package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChangeType;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateInfobaseFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateProjectFlow;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import ru.fedukhin.edt.mcp.tools.infobase.internal.HeadlessInfobaseChangesResolver;

public class HeadlessInfobaseChangesResolverTest {

    private final IProject project = mock(IProject.class);
    private final InfobaseReference ref = mock(InfobaseReference.class);
    private final IInfobaseSynchronizationStateManager stateManager = mock(IInfobaseSynchronizationStateManager.class);
    private final IV8ProjectManager projects = mock(IV8ProjectManager.class);
    private final IInfobaseConfigurationChange change = mock(IInfobaseConfigurationChange.class);
    private final IInfobaseUpdateConflictResolver conflictResolver = mock(IInfobaseUpdateConflictResolver.class);
    private final IInfobaseUpdateConflictResolver.IConflictResolveAssist assist =
        mock(IInfobaseUpdateConflictResolver.IConflictResolveAssist.class);
    private final IInfobaseSynchronizationFlow flow = mock(IInfobaseSynchronizationFlow.class);
    private final IProgressMonitor monitor = new NullProgressMonitor();
    private final Set<EObject> changed = new HashSet<>();
    private final Set<EObject> removed = new HashSet<>();
    private final Set<String> fqns = new HashSet<>(Set.of("Catalog.Товары"));

    private InfobaseConflictResolution resolve() throws InfobaseSynchronizationException {
        return new HeadlessInfobaseChangesResolver(stateManager, projects)
            .resolveInfobaseChanges(project, ref, changed, removed, fqns, change, conflictResolver, assist, flow, monitor);
    }

    @Test
    public void emptyChange_returnsOverridden_withoutTouchingFlows() throws Exception {
        when(change.isEmpty()).thenReturn(true);

        assertEquals(InfobaseConflictResolutionResult.OVERRIDDEN, resolve().getResolutionResult());
        verify(flow, never()).cancel();
        verifyZeroInteractions(stateManager, conflictResolver);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void changes_areResolvedInUpdateProjectFlow_withCoreAssist() throws Exception {
        when(change.isEmpty()).thenReturn(false);
        IV8Project v8 = mock(IV8Project.class);
        IDtProject dt = mock(IDtProject.class);
        when(projects.getProject(project)).thenReturn(v8);
        when(v8.getDtProject()).thenReturn(dt);
        IUpdateProjectFlow updateFlow = mock(IUpdateProjectFlow.class);
        when(stateManager.startUpdateProjectFlow(dt, ref)).thenReturn(updateFlow);
        ArgumentCaptor<Predicate<InfobaseConflictResolution>> keep = ArgumentCaptor.forClass((Class) Predicate.class);
        doAnswer(inv -> ((IInfobaseSynchronizationFlow.ComputableWithFlow<?, ?, ?>) inv.getArgument(0)).compute())
            .when(updateFlow).computeInFlowWithoutFinishing(any(), keep.capture());
        InfobaseConflictResolution imported = new InfobaseConflictResolution(InfobaseConflictResolutionResult.IMPORTED);
        ArgumentCaptor<IInfobaseUpdateConflictResolver.IConflictResolveAssist> passed =
            ArgumentCaptor.forClass(IInfobaseUpdateConflictResolver.IConflictResolveAssist.class);
        when(conflictResolver.resolveConflict(eq(project), eq(ref), eq(changed), eq(removed), eq(fqns), eq(change),
            passed.capture(), eq(updateFlow), eq(monitor))).thenReturn(imported);

        InfobaseConflictResolution result = resolve();

        assertSame(imported, result);
        verify(flow).cancel();
        // L3: ядру уходит не штатный ассистент, а обёртка над ним: слияние штатное, плюс удаление
        // устаревших файлов и обновление ресурсов до того, как EDT запишет состояние синхронизации.
        assertTrue("ассистент ядра обёрнут: " + passed.getValue(),
            passed.getValue() instanceof HeadlessInfobaseChangesResolver.ReplacingAssist);
        java.nio.file.Path temp = java.nio.file.Paths.get("E:/tmp/xml-dmpinf-mrg-1");
        when(assist.createTempDirectory("dt-mrg-")).thenReturn(temp);
        assertSame("обёртка делегирует штатному ассистенту", temp, passed.getValue().createTempDirectory("dt-mrg-"));
        assertFalse(keep.getValue().test(new InfobaseConflictResolution(InfobaseConflictResolutionResult.IGNORED)));
        assertTrue(keep.getValue().test(imported));
        // Текущий поток синхронизации отменяется ДО открытия updateFlow — открывать новый поток,
        // не закрыв старый, было бы ошибкой состояния синхронизации.
        InOrder order = inOrder(flow, stateManager);
        order.verify(flow).cancel();
        order.verify(stateManager).startUpdateProjectFlow(dt, ref);
    }

    // ---- L3: слияние заменяет проект целиком — удалённое в базе удаляется и из проекта ----

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Каталог проекта и временный каталог слияния ({@code dt-mrg-…}), куда EDT кладёт версию из базы. */
    private java.nio.file.Path projectDir;
    private java.nio.file.Path mergeDir;
    private final IProject diskProject = mock(IProject.class);

    private void dirs() throws Exception {
        projectDir = tmp.newFolder("project").toPath();
        mergeDir = tmp.newFolder("dt-mrg").toPath();
        when(diskProject.getName()).thenReturn("Demo");
        when(diskProject.getLocation()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(projectDir.toString()));
    }

    private static void file(java.nio.file.Path root, String relative, String content) throws Exception {
        java.nio.file.Path f = root.resolve(relative);
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code .mdo} с корневым элементом {@code mdclass:<kind>} — по нему ищется папка удалённого объекта. */
    private static void mdo(java.nio.file.Path root, String relative, String kind) throws Exception {
        file(root, relative, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mdclass:" + kind
            + " xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"1\">\n  <name>X</name>\n</mdclass:"
            + kind + ">\n");
    }

    private boolean exists(String relative) {
        return Files.exists(projectDir.resolve(relative));
    }

    /**
     * Штатный ассистент ядра, как он есть: {@code FileUtil.copyRecursively(temp, project)} — только
     * добавляет и перезаписывает, ничего не удаляет.
     */
    private final class CopyingCoreAssist implements IInfobaseUpdateConflictResolver.IConflictResolveAssist {
        private final IStatus status;
        boolean merged;

        CopyingCoreAssist(IStatus status) {
            this.status = status;
        }

        @Override
        public CompletableFuture<IStatus> mergeInfobaseChanges(IProject p, Set<String> projectChanges,
                java.nio.file.Path tempFolder, Set<String> infobaseChanges, boolean fullReload) {
            merged = true;
            if (status.matches(IStatus.ERROR)) return CompletableFuture.completedFuture(status);
            try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(tempFolder)) {
                for (java.nio.file.Path source : (Iterable<java.nio.file.Path>) walk::iterator) {
                    java.nio.file.Path target = projectDir.resolve(tempFolder.relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            return CompletableFuture.completedFuture(status);
        }

        @Override public void exportFullXmlFromInfobase(java.nio.file.Path p) { throw new UnsupportedOperationException(); }
        @Override public void exportIncrementalXmlFromInfobase(Set<String> f, java.nio.file.Path p) {
            throw new UnsupportedOperationException();
        }
        @Override public void importIncrementalObjectsToInfobase(Set<EObject> c, Set<EObject> r, Set<String> f,
                IUpdateInfobaseFlow flow) {
            throw new UnsupportedOperationException();
        }
        @Override public void importFullConfigurationToInfobase(Configuration c, IUpdateInfobaseFlow flow) {
            throw new UnsupportedOperationException();
        }
        @Override public java.nio.file.Path createTempDirectory(String prefix) { throw new UnsupportedOperationException(); }
    }

    // ---- L8, fix round 4: что записало слияние и что EDT считала изменённым на своей стороне ----

    /**
     * Обёртка записывает ключи всего, что слияние записало (файлы временного каталога — в формате EDT) и удалило
     * (устаревшие файлы — путь от корня проекта через {@code /}): по ним {@code ProjectFromInfobaseUpdater}
     * отличает свои изменения от чужих.
     */
    @Test
    public void merge_recordsWrittenAndDeletedKeys_inEdtKeyFormat() throws Exception {
        dirs();
        mdo(projectDir, "src/Configuration/Configuration.mdo", "Configuration");
        mdo(projectDir, "src/CommonModules/Старый/Старый.mdo", "CommonModule");
        file(projectDir, "src/CommonModules/Старый/Module.bsl", "// удалён в базе");
        mdo(mergeDir, "src/Configuration/Configuration.mdo", "Configuration");
        mdo(mergeDir, "src/Catalogs/Товары/Товары.mdo", "Catalog");
        HeadlessInfobaseChangesResolver.Observations seen = new HeadlessInfobaseChangesResolver.Observations();

        IStatus status = new HeadlessInfobaseChangesResolver.ReplacingAssist(new CopyingCoreAssist(Status.OK_STATUS),
            change(true), monitor, seen).mergeInfobaseChanges(diskProject, Set.of(), mergeDir, Set.of(), true).get();

        assertTrue(status.toString(), status.isOK());
        assertTrue(seen.fullReload());
        assertEquals(Set.of("src/Configuration/Configuration.mdo", "src/Catalogs/Товары/Товары.mdo",
            "src/CommonModules/Старый/Старый.mdo", "src/CommonModules/Старый/Module.bsl"), seen.mergedKeys());
        assertEquals(null, seen.mergedKeysFailure());
    }

    /**
     * M4 (fix round 5): ключи файлов временного каталога — своим обходом, без SHA-256 каждого файла (EDT уже
     * посчитала их в processIncomingInfobaseResources, а второй проход по гигабайтам полной перезагрузки шёл бы под
     * замком базы). Ключи обязаны совпадать с ключами {@code getSignaturesForExternalFiles} EDT — сверка на дереве
     * с кириллицей, пробелами, вложенностью, файлом в корне и пустым каталогом.
     */
    @Test
    public void fileKeys_matchEdtExternalFileKeys_onSampleTree() throws Exception {
        java.nio.file.Path dir = tmp.newFolder("dt-mrg-sample").toPath();
        mdo(dir, "src/Configuration/Configuration.mdo", "Configuration");
        file(dir, "src/Configuration/ManagedApplicationModule.bsl", "// модуль");
        file(dir, "src/CommonModules/Общий модуль/Module.bsl", "// пробел и кириллица");
        file(dir, "src/Catalogs/Товары/Forms/ФормаЭлемента/Ext/Form/Module.bsl", "// вложенность");
        file(dir, "ConfigDumpInfo.xml", "<ConfigDumpInfo/>");
        Files.createDirectories(dir.resolve("src/Пустой"));

        Set<String> ours = HeadlessInfobaseChangesResolver.ReplacingAssist.fileKeys(dir);

        assertEquals(ResourceStoreFakes.externalSignatures(dir).keySet(), ours);
        assertTrue(ours.toString(), ours.contains("src/CommonModules/Общий модуль/Module.bsl"));
        assertEquals(5, ours.size());
    }

    /** Каталог слияния не обойти — причина записана (отметки не будет), а само слияние от этого не падает. */
    @Test
    public void merge_tempFolderNotWalkable_recordsReason() throws Exception {
        dirs();
        java.nio.file.Path missing = mergeDir.resolveSibling("dt-mrg-нет");
        when(assist.mergeInfobaseChanges(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(CompletableFuture.completedFuture(Status.OK_STATUS));
        HeadlessInfobaseChangesResolver.Observations seen = new HeadlessInfobaseChangesResolver.Observations();

        IStatus status = new HeadlessInfobaseChangesResolver.ReplacingAssist(assist, change(true), monitor, seen)
            .mergeInfobaseChanges(diskProject, Set.of(), missing, Set.of(), true).get();

        assertFalse("слияние отработало (без src во временном каталоге — WARNING)", status.matches(IStatus.ERROR));
        assertTrue(String.valueOf(seen.mergedKeysFailure()), seen.mergedKeysFailure() != null);
    }

    /**
     * EDT передаёт обработчику изменения на своей стороне ({@code collectEdtObjectChanges}): непустые — проект
     * для EDT не совпадал с базой ещё до обновления. Записывается и тогда, когда изменений базы нет.
     */
    @Test
    public void resolve_recordsEdtSideChanges() throws Exception {
        when(change.isEmpty()).thenReturn(true);
        HeadlessInfobaseChangesResolver.Observations withChanges = new HeadlessInfobaseChangesResolver.Observations();
        HeadlessInfobaseChangesResolver.Observations without = new HeadlessInfobaseChangesResolver.Observations();

        new HeadlessInfobaseChangesResolver(stateManager, projects, withChanges).resolveInfobaseChanges(project,
            ref, Set.of(), Set.of(), Set.of("CommonModule.Удалённый"), change, conflictResolver, assist, flow, monitor);
        new HeadlessInfobaseChangesResolver(stateManager, projects, without).resolveInfobaseChanges(project,
            ref, Set.of(), Set.of(), Set.of(), change, conflictResolver, assist, flow, monitor);

        assertTrue(withChanges.edtChanges());
        assertFalse(without.edtChanges());
    }

    private IInfobaseConfigurationChange change(boolean fullReload, ObjectChange... objectChanges) {
        IInfobaseConfigurationChange c = mock(IInfobaseConfigurationChange.class);
        when(c.isEmpty()).thenReturn(false);
        when(c.isFullReloadRequired()).thenReturn(fullReload);
        when(c.getObjectChanges()).thenReturn(new HashSet<>(List.of(objectChanges)));
        return c;
    }

    private IStatus merge(CopyingCoreAssist core, IInfobaseConfigurationChange c) throws Exception {
        boolean full = c.isFullReloadRequired();
        CompletableFuture<IStatus> future = new HeadlessInfobaseChangesResolver.ReplacingAssist(core, c, monitor)
            .mergeInfobaseChanges(diskProject, Set.of(), mergeDir, Set.of(), full);
        assertTrue("будущее уже завершено — EDT запишет состояние синхронизации после нашей чистки", future.isDone());
        return future.get();
    }

    /**
     * Полная перезагрузка: во временном каталоге — вся конфигурация базы. Файлы проекта под
     * {@code src}, которых там нет, — устаревшие (объект удалён в базе), удаляются вместе с опустевшими
     * каталогами; остальное и всё вне {@code src} не трогается. Ресурсы обновлены ДО возврата будущего.
     */
    @Test
    public void fullReload_deletesProjectFilesAbsentInInfobase_andRefreshesBeforeReturning() throws Exception {
        dirs();
        mdo(projectDir, "src/Configuration/Configuration.mdo", "Configuration");
        mdo(projectDir, "src/CommonModules/ОбщийМодульSmoke/ОбщийМодульSmoke.mdo", "CommonModule");
        file(projectDir, "src/CommonModules/ОбщийМодульSmoke/Module.bsl", "Процедура А() КонецПроцедуры");
        mdo(projectDir, "src/Catalogs/Товары/Товары.mdo", "Catalog");
        file(projectDir, "DT-INF/PROJECT.PMF", "Manifest-Version: 1.0");
        mdo(mergeDir, "src/Configuration/Configuration.mdo", "Configuration");
        mdo(mergeDir, "src/Catalogs/Товары/Товары.mdo", "Catalog");
        CopyingCoreAssist core = new CopyingCoreAssist(Status.OK_STATUS);

        IStatus status = merge(core, change(true));

        assertTrue(status.toString(), status.isOK());
        assertTrue(core.merged);
        assertFalse("модуля нет в базе — его папка удалена", exists("src/CommonModules/ОбщийМодульSmoke"));
        assertFalse("опустевший каталог вида объектов тоже", exists("src/CommonModules"));
        assertTrue(exists("src/Catalogs/Товары/Товары.mdo"));
        assertTrue(exists("src/Configuration/Configuration.mdo"));
        assertTrue("вне src ничего не трогается", exists("DT-INF/PROJECT.PMF"));
        assertTrue(exists("src"));
        verify(diskProject).refreshLocal(IResource.DEPTH_INFINITE, monitor);
    }

    /**
     * Инкрементальная загрузка: во временном каталоге — только изменённые объекты целиком (объект с
     * внешними свойствами — модулями, справкой, командами, — плюс формы/макеты, которые EDT добавляет
     * сама). Удаляется: папка объекта, удалённого в базе (DELETED, по корневому элементу {@code .mdo}),
     * и устаревшие файлы внутри изменённых объектов. Не трогаются: нетронутые объекты; вложенные
     * подсистемы (в выгрузку подсистемы они не входят — проверено на 8.5.1); файлы поддержки.
     */
    @Test
    public void incremental_deletesDeletedObjectAndStaleFilesOfChangedObjects_keepsTheRest() throws Exception {
        dirs();
        mdo(projectDir, "src/Catalogs/Товары/Товары.mdo", "Catalog");
        file(projectDir, "src/Catalogs/Товары/ObjectModule.bsl", "// удалён в базе");
        file(projectDir, "src/Catalogs/Товары/Forms/ФормаЭлемента/Form.form", "<form/>");
        file(projectDir, "src/Catalogs/Товары/Forms/Старая/Form.form", "<form/>");
        file(projectDir, "src/Catalogs/Товары/Commands/Открыть/CommandModule.bsl", "// команда");
        mdo(projectDir, "src/CommonModules/Удалённый/Удалённый.mdo", "CommonModule");
        file(projectDir, "src/CommonModules/Удалённый/Module.bsl", "// удалён в базе");
        mdo(projectDir, "src/CommonModules/Нетронутый/Нетронутый.mdo", "CommonModule");
        file(projectDir, "src/CommonModules/Нетронутый/Module.bsl", "// не менялся");
        mdo(projectDir, "src/Subsystems/Раздел/Раздел.mdo", "Subsystem");
        mdo(projectDir, "src/Subsystems/Раздел/Subsystems/Вложенный/Вложенный.mdo", "Subsystem");
        mdo(projectDir, "src/Configuration/Configuration.mdo", "Configuration");
        file(projectDir, "src/Configuration/SessionModule.bsl", "// модуль сеанса");
        file(projectDir, "src/Configuration/Configuration.distr", "<distributionSupport/>");
        file(projectDir, "src/Configuration/ParentConfigurations/УТ.cf", "cf");
        mdo(mergeDir, "src/Catalogs/Товары/Товары.mdo", "Catalog");
        file(mergeDir, "src/Catalogs/Товары/Forms/ФормаЭлемента/Form.form", "<form v2/>");
        file(mergeDir, "src/Catalogs/Товары/Commands/Открыть/CommandModule.bsl", "// команда");
        mdo(mergeDir, "src/Subsystems/Раздел/Раздел.mdo", "Subsystem");
        mdo(mergeDir, "src/Configuration/Configuration.mdo", "Configuration");
        file(mergeDir, "src/Configuration/SessionModule.bsl", "// модуль сеанса");
        IInfobaseConfigurationChange c = change(false,
            new ObjectChange("CommonModule.Удалённый", ObjectChangeType.DELETED),
            new ObjectChange("Catalog.Товары", ObjectChangeType.MODIFIED),
            new ObjectChange("Catalog.Товары.ObjectModule", ObjectChangeType.DELETED),
            new ObjectChange("Catalog.Товары.Form.Старая", ObjectChangeType.DELETED),
            new ObjectChange("Subsystem.Раздел", ObjectChangeType.MODIFIED),
            new ObjectChange("Configuration.Конфигурация", ObjectChangeType.MODIFIED));

        IStatus status = merge(new CopyingCoreAssist(Status.OK_STATUS), c);

        assertTrue(status.toString(), status.isOK());
        assertFalse("объект удалён в базе", exists("src/CommonModules/Удалённый"));
        assertFalse("модуль изменённого объекта удалён в базе", exists("src/Catalogs/Товары/ObjectModule.bsl"));
        assertFalse("форма изменённого объекта удалена в базе", exists("src/Catalogs/Товары/Forms/Старая"));
        assertTrue(exists("src/Catalogs/Товары/Товары.mdo"));
        assertTrue(exists("src/Catalogs/Товары/Forms/ФормаЭлемента/Form.form"));
        assertTrue(exists("src/Catalogs/Товары/Commands/Открыть/CommandModule.bsl"));
        assertTrue("нетронутый объект", exists("src/CommonModules/Нетронутый/Module.bsl"));
        assertTrue("вложенная подсистема в выгрузку изменённой подсистемы не входит — не удалять",
            exists("src/Subsystems/Раздел/Subsystems/Вложенный/Вложенный.mdo"));
        assertTrue(exists("src/Configuration/SessionModule.bsl"));
        assertTrue("файлы поддержки не трогаются", exists("src/Configuration/Configuration.distr"));
        assertTrue("файлы поддержки не трогаются", exists("src/Configuration/ParentConfigurations/УТ.cf"));
        verify(diskProject).refreshLocal(IResource.DEPTH_INFINITE, monitor);
    }

    /** Удалённая в базе вложенная подсистема — её папка внутри родительской подсистемы. */
    @Test
    public void incremental_deletedNestedSubsystem_removesItsFolder() throws Exception {
        dirs();
        mdo(projectDir, "src/Subsystems/Раздел/Раздел.mdo", "Subsystem");
        mdo(projectDir, "src/Subsystems/Раздел/Subsystems/Вложенный/Вложенный.mdo", "Subsystem");
        mdo(projectDir, "src/Subsystems/Раздел/Subsystems/Живой/Живой.mdo", "Subsystem");
        mdo(mergeDir, "src/Subsystems/Раздел/Раздел.mdo", "Subsystem");

        IStatus status = merge(new CopyingCoreAssist(Status.OK_STATUS), change(false,
            new ObjectChange("Subsystem.Раздел.Subsystem.Вложенный", ObjectChangeType.DELETED),
            new ObjectChange("Subsystem.Раздел", ObjectChangeType.MODIFIED)));

        assertTrue(status.toString(), status.isOK());
        assertFalse(exists("src/Subsystems/Раздел/Subsystems/Вложенный"));
        assertTrue(exists("src/Subsystems/Раздел/Subsystems/Живой/Живой.mdo"));
    }

    /**
     * Ревью fix round 2: в изменениях одновременно {@code DELETED} и {@code NEW} одного {@code Вид.Имя}
     * (объект удалён и создан заново с новым UUID — {@code ObjectChange.equals} по (имя, тип), так что
     * оба остаются). Ядро только что записало новый объект по тем же путям — удалять можно лишь то,
     * чего во временном каталоге нет.
     */
    @Test
    public void incremental_deletedAndRecreatedObject_keepsFreshlyMergedFiles() throws Exception {
        dirs();
        mdo(projectDir, "src/Catalogs/Цены/Цены.mdo", "Catalog");
        file(projectDir, "src/Catalogs/Цены/ObjectModule.bsl", "// старый объект");
        file(projectDir, "src/Catalogs/Цены/Forms/Старая/Form.form", "<form/>");
        file(mergeDir, "src/Catalogs/Цены/Цены.mdo", "<?xml version=\"1.0\"?>\n<mdclass:Catalog uuid=\"new\"/>\n");
        file(mergeDir, "src/Catalogs/Цены/ManagerModule.bsl", "// новый объект");

        IStatus status = merge(new CopyingCoreAssist(Status.OK_STATUS), change(false,
            new ObjectChange("Catalog.Цены", ObjectChangeType.DELETED),
            new ObjectChange("Catalog.Цены", ObjectChangeType.NEW)));

        assertTrue(status.toString(), status.isOK());
        assertTrue("новый объект только что записан ядром — не удалять", exists("src/Catalogs/Цены/Цены.mdo"));
        assertTrue(new String(Files.readAllBytes(projectDir.resolve("src/Catalogs/Цены/Цены.mdo")),
            StandardCharsets.UTF_8).contains("uuid=\"new\""));
        assertTrue(exists("src/Catalogs/Цены/ManagerModule.bsl"));
        assertFalse("файл старого объекта, которого нет в новом, удаляется", exists("src/Catalogs/Цены/ObjectModule.bsl"));
        assertFalse(exists("src/Catalogs/Цены/Forms/Старая"));
    }

    /**
     * Переименование только регистром ({@code DELETED Catalog.товары} + {@code NEW Catalog.Товары}): на NTFS
     * это та же папка, и ядро уже записало в неё новый объект. Пути сравниваются без учёта регистра.
     */
    @Test
    public void incremental_caseOnlyRename_keepsTheObject() throws Exception {
        dirs();
        mdo(projectDir, "src/Catalogs/товары/товары.mdo", "Catalog");
        file(projectDir, "src/Catalogs/товары/ObjectModule.bsl", "// модуль");
        mdo(mergeDir, "src/Catalogs/Товары/Товары.mdo", "Catalog");
        file(mergeDir, "src/Catalogs/Товары/ObjectModule.bsl", "// модуль");

        IStatus status = merge(new CopyingCoreAssist(Status.OK_STATUS), change(false,
            new ObjectChange("Catalog.товары", ObjectChangeType.DELETED),
            new ObjectChange("Catalog.Товары", ObjectChangeType.NEW)));

        assertTrue(status.toString(), status.isOK());
        assertTrue("объект после переименования регистром на месте", exists("src/Catalogs/Товары/Товары.mdo"));
        assertTrue(exists("src/Catalogs/Товары/ObjectModule.bsl"));
    }

    /**
     * Ревью fix round 2: {@code Files.walk} заходит в точки соединения NTFS как в каталоги. Удаление
     * обязано оставаться внутри настоящего {@code src}: ссылки и точки соединения не обходятся, файлы
     * за ними не удаляются.
     */
    @Test
    public void fullReload_doesNotFollowJunctionOutOfSrc() throws Exception {
        dirs();
        Path outside = tmp.newFolder("outside").toPath();
        file(outside, "Важное.txt", "не трогать");
        mdo(projectDir, "src/Configuration/Configuration.mdo", "Configuration");
        mdo(mergeDir, "src/Configuration/Configuration.mdo", "Configuration");
        Path junction = projectDir.resolve("src/CommonModules/Link");
        Files.createDirectories(junction.getParent());
        linkOrSkip(junction, outside);
        try {
            merge(new CopyingCoreAssist(Status.OK_STATUS), change(true));

            assertTrue("файл за точкой соединения не удалён", Files.exists(outside.resolve("Важное.txt")));
        } finally {
            Files.deleteIfExists(junction); // саму ссылку, не содержимое цели
        }
    }

    /** То же для папки удалённого объекта, найденной по имени из платформы: за ссылку не выходить. */
    @Test
    public void incremental_deletedObjectBehindJunction_notDeleted() throws Exception {
        dirs();
        Path outside = tmp.newFolder("outside-object").toPath();
        mdo(outside, "Link.mdo", "CommonModule");
        file(outside, "Module.bsl", "// чужой каталог");
        mdo(mergeDir, "src/Configuration/Configuration.mdo", "Configuration");
        Path junction = projectDir.resolve("src/CommonModules/Link");
        Files.createDirectories(junction.getParent());
        linkOrSkip(junction, outside);
        try {
            merge(new CopyingCoreAssist(Status.OK_STATUS), change(false,
                new ObjectChange("CommonModule.Link", ObjectChangeType.DELETED)));

            assertTrue(Files.exists(outside.resolve("Link.mdo")));
            assertTrue(Files.exists(outside.resolve("Module.bsl")));
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    /**
     * Точка соединения (Windows, {@code mklink /J} — прав администратора не требует) или символьная
     * ссылка; ОС не дала создать — тест пропускается.
     */
    private static void linkOrSkip(Path link, Path target) throws Exception {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.Assume.assumeTrue("mklink /J не сработал", finished && p.exitValue() == 0 && Files.exists(link));
            return;
        }
        try {
            Files.createSymbolicLink(link, target);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
            org.junit.Assume.assumeNoException("ОС не дала создать ссылку", e);
        }
    }

    /** Удалённый объект ищется по виду: одноимённый объект другого вида не трогается. */
    @Test
    public void incremental_deletedObject_onlyFolderOfSameKind() throws Exception {
        dirs();
        mdo(projectDir, "src/Catalogs/Цены/Цены.mdo", "Catalog");
        mdo(projectDir, "src/Documents/Цены/Цены.mdo", "Document");
        mdo(mergeDir, "src/Configuration/Configuration.mdo", "Configuration");

        merge(new CopyingCoreAssist(Status.OK_STATUS), change(false,
            new ObjectChange("Catalog.Цены", ObjectChangeType.DELETED)));

        assertFalse(exists("src/Catalogs/Цены"));
        assertTrue(exists("src/Documents/Цены/Цены.mdo"));
    }

    /** Слияние ядра не удалось — ничего не удаляется, ресурсы не трогаются, статус ядра как есть. */
    @Test
    public void coreMergeError_deletesNothing() throws Exception {
        dirs();
        mdo(projectDir, "src/CommonModules/ОбщийМодульSmoke/ОбщийМодульSmoke.mdo", "CommonModule");
        mdo(mergeDir, "src/Configuration/Configuration.mdo", "Configuration");
        IStatus error = new Status(IStatus.ERROR, "test", "Замещение содержимого проекта не удалось");

        IStatus status = merge(new CopyingCoreAssist(error), change(true));

        assertSame(error, status);
        assertTrue(exists("src/CommonModules/ОбщийМодульSmoke/ОбщийМодульSmoke.mdo"));
        verify(diskProject, never()).refreshLocal(anyInt(), any());
    }

    /** Во временном каталоге нет {@code src} — сверять не с чем: ничего не удаляется, статус WARNING. */
    @Test
    public void mergeFolderWithoutSrc_deletesNothing_andWarns() throws Exception {
        dirs();
        mdo(projectDir, "src/CommonModules/ОбщийМодульSmoke/ОбщийМодульSmoke.mdo", "CommonModule");

        IStatus status = merge(new CopyingCoreAssist(Status.OK_STATUS), change(true));

        assertEquals(IStatus.WARNING, status.getSeverity());
        assertTrue(status.getMessage(), status.getMessage().contains("src"));
        assertTrue(exists("src/CommonModules/ОбщийМодульSmoke/ОбщийМодульSmoke.mdo"));
    }

    /** Файл не удалился (занят) — статус WARNING с его именем; остальное удалено. */
    @Test
    public void undeletableStaleFile_downgradesToWarningNamingIt() throws Exception {
        org.junit.Assume.assumeTrue("блокировка открытого файла — поведение Windows",
            System.getProperty("os.name", "").startsWith("Windows"));
        dirs();
        mdo(projectDir, "src/CommonModules/Занятый/Занятый.mdo", "CommonModule");
        file(projectDir, "src/CommonModules/Занятый/Module.bsl", "// открыт");
        mdo(mergeDir, "src/Configuration/Configuration.mdo", "Configuration");
        java.nio.file.Path locked = projectDir.resolve("src/CommonModules/Занятый/Module.bsl");

        IStatus status;
        try (java.io.OutputStream hold = new java.io.FileOutputStream(locked.toFile(), true)) {
            status = merge(new CopyingCoreAssist(Status.OK_STATUS), change(true));
        }

        assertEquals(IStatus.WARNING, status.getSeverity());
        assertTrue(status.getMessage(), status.getMessage().contains("Module.bsl"));
        assertFalse(exists("src/CommonModules/Занятый/Занятый.mdo"));
    }

    @Test
    public void projectNotOpenInEdt_failsBeforeCancellingFlow() {
        when(change.isEmpty()).thenReturn(false);
        when(projects.getProject(project)).thenReturn(null);
        when(project.getName()).thenReturn("Demo");
        try {
            resolve();
            fail("expected InfobaseSynchronizationException");
        } catch (InfobaseSynchronizationException e) {
            assertTrue(e.getStatus().getMessage(), e.getStatus().getMessage().contains("Demo"));
        }
        verify(flow, never()).cancel();
    }
}
