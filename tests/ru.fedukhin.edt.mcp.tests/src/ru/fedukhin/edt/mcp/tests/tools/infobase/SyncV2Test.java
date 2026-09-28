package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;

public class SyncV2Test {

    private final InfobaseReference ref = mock(InfobaseReference.class);

    private static IProject project(String name) {
        IProject p = mock(IProject.class);
        when(p.getName()).thenReturn(name);
        return p;
    }

    @Test
    public void unavailable_whenClassIsMissing() {
        SyncV2 v2 = new SyncV2(() -> { throw new NoClassDefFoundError("IInfobaseSynchronizationStateManager"); });

        assertFalse(v2.isAvailable());
        try {
            v2.requireAvailable();
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("2026.1"));
        }
    }

    @Test
    public void unavailable_whenServiceHasWrongType() {
        assertFalse(new SyncV2(() -> "не сервис").isAvailable());
    }

    @Test
    public void lookup_isCachedAfterSuccess() {
        AtomicInteger calls = new AtomicInteger();
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        SyncV2 v2 = new SyncV2(() -> { calls.incrementAndGet(); return sm; });

        assertTrue(v2.isAvailable());
        assertTrue(v2.isAvailable());
        assertEquals(1, calls.get());
    }

    @Test
    public void projectsAtRisk_reportsDirtyAndUnknownState() throws Exception {
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        IProject dirty = project("Dirty");
        IProject unknown = project("Unknown");
        IProject clean = project("Clean");
        when(sm.hasSynchronizationInfo(dirty, ref)).thenReturn(true);
        when(sm.isProjectDirty(dirty, ref)).thenReturn(true);
        when(sm.hasSynchronizationInfo(unknown, ref)).thenReturn(false);
        when(sm.hasSynchronizationInfo(clean, ref)).thenReturn(true);
        when(sm.isProjectDirty(clean, ref)).thenReturn(false);

        List<String> atRisk = new SyncV2(() -> sm).projectsAtRisk(List.of(dirty, unknown, clean), ref);

        assertEquals(2, atRisk.size());
        assertTrue(atRisk.get(0), atRisk.get(0).startsWith("Dirty"));
        assertTrue(atRisk.get(1), atRisk.get(1).startsWith("Unknown"));
    }

    /**
     * M1 (fix round 5): «EDT считает проект несинхронизированным» и «проверить не удалось» — разные вещи;
     * {@code riskOf} различает их ({@code unverifiedReason}), текст пункта у {@code projectsAtRisk} прежний.
     */
    @Test
    public void riskOf_distinguishesUnverifiedFromUnsynchronized() throws Exception {
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        IProject clean = project("Clean");
        IProject dirty = project("Dirty");
        IProject broken = project("Broken");
        when(sm.hasSynchronizationInfo(clean, ref)).thenReturn(true);
        when(sm.hasSynchronizationInfo(dirty, ref)).thenReturn(true);
        when(sm.isProjectDirty(dirty, ref)).thenReturn(true);
        when(sm.hasSynchronizationInfo(broken, ref)).thenThrow(new IllegalStateException("состояние читается"));
        SyncV2 v2 = new SyncV2(() -> sm);

        assertEquals(null, v2.riskOf(clean, ref));
        assertEquals(new SyncV2.Risk("Dirty (есть правки, не залитые в базу)", null), v2.riskOf(dirty, ref));
        assertEquals(new SyncV2.Risk("Broken (не удалось проверить: состояние читается)", "состояние читается"),
            v2.riskOf(broken, ref));
    }

    @Test
    public void atRiskMessage_pointsToDiscardFlag() {
        String message = SyncV2.atRiskMessage(List.of("Beta (есть правки, не залитые в базу)"));
        assertTrue(message, message.contains("discardProjectChanges"));
        assertTrue(message, message.contains("Beta"));
    }

    /**
     * {@code hasSynchronizationInfo = false} — это «у EDT нет ConfigDumpInfo для пары проект/база»,
     * то есть ровно случай нового проекта, ни разу не синхронизированного с базой. Текст пункта
     * обязан говорить именно это, а не абстрактное «состояние неизвестно».
     */
    @Test
    public void projectsAtRisk_withoutSyncInfo_saysProjectNeverSynchronized() throws Exception {
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        IProject fresh = project("Fresh");
        when(sm.hasSynchronizationInfo(fresh, ref)).thenReturn(false);

        List<String> atRisk = new SyncV2(() -> sm).projectsAtRisk(List.of(fresh), ref);

        assertEquals(List.of("Fresh (проект ещё не синхронизировался с этой базой: у EDT нет сведений, "
            + "что в нём совпадает с базой)"), atRisk);
    }

    /**
     * Для нового или пустого проекта совет «залейте правки (deploy_project)» разрушителен:
     * deploy_project зальёт пустой проект В базу. Текст обязан вести к discardProjectChanges и
     * прямо предупреждать, что deploy_project заменяет конфигурацию базы.
     */
    @Test
    public void atRiskMessage_offersDiscardForNewProject_andWarnsThatDeployOverwritesInfobase() {
        String message = SyncV2.atRiskMessage(List.of("A (x)", "B (y)"));

        assertTrue(message, message.contains("A (x); B (y)"));
        assertTrue(message, message.contains("повторите с discardProjectChanges: true"));
        assertTrue(message, message.contains("deploy_project заменяет конфигурацию базы содержимым проекта"));
        assertTrue(message, message.contains("для пустого или только что созданного проекта его не вызывайте"));
        assertFalse(message, message.contains("Залейте правки (deploy_project) или"));
    }

    /**
     * Fix round 6b: отказ обновить проект ПОСЛЕ загрузки .dt/.cfe. Общий совет «сначала залейте правки
     * (deploy_project)» здесь разрушителен — проект ещё прежний, и заливка заменила бы только что загруженную
     * конфигурацию. Текст говорит, что загрузка уже выполнена, проект не обновлён и не тронут, запрещает
     * deploy_project сейчас и ведёт к «сохранить правки — update_project_from_infobase с discardProjectChanges».
     */
    @Test
    public void atRiskAfterLoadMessage_loadDone_projectUntouched_noDeployNow() {
        String message = SyncV2.atRiskAfterLoadMessage("файл .dt «smoke-v1.dt» уже загружен в базу McpF1",
            List.of("F1 (есть правки, не залитые в базу)"));

        assertTrue(message, message.startsWith("файл .dt «smoke-v1.dt» уже загружен в базу McpF1"));
        assertTrue(message, message.contains("проект из базы не обновлён"));
        assertTrue(message, message.contains("F1 (есть правки, не залитые в базу)"));
        assertTrue(message, message.contains("Содержимое проекта не тронуто"));
        assertTrue(message, message.contains("Не вызывайте сейчас deploy_project"));
        assertTrue(message, message.contains("заменит только что загруженную конфигурацию базы"));
        assertTrue(message, message.contains("git"));
        assertTrue(message, message.contains("update_project_from_infobase с discardProjectChanges: true"));
        assertFalse(message, message.contains("сначала залейте их в базу (deploy_project)"));
        assertFalse(message, message.contains("содержимое проектов будет заменено версией из базы"));
    }

    @Test
    public void projectsAtRisk_catchesLinkageErrorPerProject() throws Exception {
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        IProject broken = project("Broken");
        when(sm.hasSynchronizationInfo(broken, ref)).thenThrow(new NoClassDefFoundError("boom"));

        List<String> atRisk = new SyncV2(() -> sm).projectsAtRisk(List.of(broken), ref);

        assertEquals(1, atRisk.size());
        assertTrue(atRisk.get(0), atRisk.get(0).startsWith("Broken"));
    }

    @Test
    public void unavailable_whenServiceHasWrongType_requireAvailableGivesGenericMessage() {
        SyncV2 v2 = new SyncV2(() -> "не сервис");

        try {
            v2.requireAvailable();
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("2026.1"));
        }
    }

    @Test
    public void requireAvailable_transientLookupFailure_suggestsRetry() {
        SyncV2 v2 = new SyncV2(() -> { throw new IllegalStateException("сервис ещё не зарегистрирован"); });

        assertFalse(v2.isAvailable());
        try {
            v2.requireAvailable();
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("пока недоступен"));
            assertTrue(e.getMessage(), e.getMessage().contains("повторите позже"));
            assertTrue(e.getMessage(), e.getMessage().contains("сервис ещё не зарегистрирован"));
        }
    }

    @Test
    public void transientLookupFailure_doesNotPoisonLaterSuccess() {
        AtomicInteger calls = new AtomicInteger();
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        SyncV2 v2 = new SyncV2(() -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("EDT ещё запускается");
            return sm;
        });

        assertFalse(v2.isAvailable());
        assertTrue(v2.isAvailable());
    }

    // ---- L8: отметка «проект совпадает с базой» — внутренний API EDT, только рефлексией ----

    /**
     * Сервис синхронизации EDT → {@code getDelegate()} → {@code forceEdtSynchronization(база, проект)}:
     * порядок аргументов — как у EDT (сначала база).
     */
    @Test
    public void markSynchronized_callsForceEdtSynchronizationOfDelegate() {
        SyncV2Fakes.Delegate delegate = mock(SyncV2Fakes.Delegate.class);
        IProject demo = project("Demo");

        SyncV2.Marking marking = new SyncV2(() -> SyncV2Fakes.stateManager(delegate)).markSynchronized(demo, ref);

        assertTrue(String.valueOf(marking.reason()), marking.marked());
        verify(delegate).forceEdtSynchronization(ref, demo);
    }

    /** Нет {@code getDelegate()} (другая версия EDT) — {@code false} с причиной, без исключения. */
    @Test
    public void markSynchronized_serviceWithoutGetDelegate_returnsFalseWithReason() {
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        when(sm.hasSynchronizationInfo(org.mockito.ArgumentMatchers.any(IProject.class),
            org.mockito.ArgumentMatchers.any())).thenReturn(true);

        SyncV2.Marking marking = new SyncV2(() -> sm).markSynchronized(project("Demo"), ref);

        assertFalse(marking.marked());
        assertTrue(marking.reason(), marking.reason().contains("getDelegate"));
    }

    @Test
    public void markSynchronized_delegateWithoutForceMethod_returnsFalseWithReason() {
        SyncV2.Marking marking = new SyncV2(() -> SyncV2Fakes.stateManager("не делегат"))
            .markSynchronized(project("Demo"), ref);

        assertFalse(marking.marked());
        assertTrue(marking.reason(), marking.reason().contains("forceEdtSynchronization"));
    }

    @Test
    public void markSynchronized_nullDelegate_returnsFalseWithReason() {
        SyncV2.Marking marking = new SyncV2(() -> SyncV2Fakes.stateManager(null)).markSynchronized(project("Demo"), ref);

        assertFalse(marking.marked());
        assertTrue(marking.reason(), marking.reason().contains("getDelegate"));
    }

    /** Исключение EDT ({@code IllegalStateException} вокруг {@code CoreException}) — в причину, не наружу. */
    @Test
    public void markSynchronized_edtFailure_isReturnedNotThrown() {
        SyncV2Fakes.Delegate delegate = mock(SyncV2Fakes.Delegate.class);
        IProject demo = project("Demo");
        doThrow(new IllegalStateException("хранилище состояния синхронизации недоступно"))
            .when(delegate).forceEdtSynchronization(ref, demo);

        SyncV2.Marking marking = new SyncV2(() -> SyncV2Fakes.stateManager(delegate)).markSynchronized(demo, ref);

        assertFalse(marking.marked());
        assertTrue(marking.reason(), marking.reason().contains("хранилище состояния синхронизации недоступно"));
    }

    @Test
    public void markSynchronized_linkageErrorInEdt_isReturnedNotThrown() {
        SyncV2Fakes.Delegate delegate = mock(SyncV2Fakes.Delegate.class);
        IProject demo = project("Demo");
        doThrow(new NoClassDefFoundError("InfobaseSyncState")).when(delegate).forceEdtSynchronization(ref, demo);

        SyncV2.Marking marking = new SyncV2(() -> SyncV2Fakes.stateManager(delegate)).markSynchronized(demo, ref);

        assertFalse(marking.marked());
        assertTrue(marking.reason(), marking.reason().contains("InfobaseSyncState"));
    }

    @Test
    public void markSynchronized_v2Unavailable_returnsFalseWithReason() {
        SyncV2.Marking marking = new SyncV2(() -> null).markSynchronized(project("Demo"), ref);

        assertFalse(marking.marked());
        assertNotNull(marking.reason());
    }

    // ---- F4 (fix round 6): forceRecheck — сброс быстрой проверки EDT по идентификатору поколения ----

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String EXTENSION_NATURE = "com._1c.g5.v8.dt.core.V8ExtensionNature";

    private SyncV2Fakes.FakeStore store() throws Exception {
        Path dir = tmp.newFolder().toPath();
        return new SyncV2Fakes.FakeStore(dir.resolve("ConfigDumpInfo.xml"), dir.resolve("extensions"));
    }

    private static IProject configurationProject(String name) throws Exception {
        IProject p = project(name);
        when(p.hasNature(EXTENSION_NATURE)).thenReturn(false);
        return p;
    }

    private static IProject extensionProject(String name) throws Exception {
        IProject p = project(name);
        when(p.hasNature(EXTENSION_NATURE)).thenReturn(true);
        return p;
    }

    /**
     * Проект конфигурации: новое состояние держателя — те же отметка времени, UUID, подписи, версии и состояния
     * расширений, пустой идентификатор поколения; записано один раз, под замком базы и монитором делегата; порядок
     * вызовов — как у {@code forceEdtSynchronization}, замок снят последним.
     */
    @Test
    public void forceRecheck_configuration_blanksOnlyGenerationId_writesOnceUnderLocks() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.FakeState old = new SyncV2Fakes.FakeState(42L, "uuid-conf", Map.of("src/a.bsl", "подпись"),
            Map.of("Catalog.Товары", "v1"), "48f38827d47fba458c83d004fa2483df00000000");
        SyncV2Fakes.FakeState ext = new SyncV2Fakes.FakeState(7L, "uuid-ext", Map.of(), Map.of(), "gen-ext");
        old.getExtensionSyncStates().put("Beta.Склад", ext);
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store, old));
        store.locksHeld = delegate::locksHeld;
        Files.writeString(store.getMainPlatformResourceVersionsPath(), "<ConfigDumpInfo/>");

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(configurationProject("Beta"), ref, false);

        assertTrue(String.valueOf(recheck.reason()), recheck.done());
        SyncV2Fakes.FakeState fresh = SyncV2Fakes.stateOf(delegate.holder);
        assertNotSame(old, fresh);
        assertEquals("", fresh.getGenerationId());
        assertEquals(42L, fresh.getTimestamp());
        assertEquals("uuid-conf", fresh.getConfigurationUUID());
        assertEquals(old.getEdtResourceMetadata(), fresh.getEdtResourceMetadata());
        assertEquals(old.getPlatformResourceVersions(), fresh.getPlatformResourceVersions());
        assertSame(ext, fresh.getExtensionSyncStates().get("Beta.Склад"));
        assertEquals(List.of(fresh), store.written);
        assertEquals("запись — под замком базы и монитором делегата", List.of(true), store.writtenUnderLocks);
        assertEquals(List.of("calculateStoreProject", "initLockStatesIfAbsent",
            "findOrCreateProjectInfobaseSynchronizationStateHolder", "lockInfobaseState", "unlockInfobaseState"),
            delegate.calls);
        assertTrue("без fullReload записанный ConfigDumpInfo на месте",
            Files.exists(store.getMainPlatformResourceVersionsPath()));
    }

    /** Проект расширения: сбрасывается только его состояние (ключ — имя ПРОЕКТА расширения, как у EDT). */
    @Test
    public void forceRecheck_extension_blanksOnlyThatExtension() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.FakeState main = new SyncV2Fakes.FakeState(42L, "uuid-conf", Map.of(), Map.of(), "gen-main");
        SyncV2Fakes.FakeState sklad = new SyncV2Fakes.FakeState(7L, "uuid-sklad", Map.of("src/x.bsl", "s"),
            Map.of("CommonModule.X", "1"), "gen-sklad");
        SyncV2Fakes.FakeState other = new SyncV2Fakes.FakeState(8L, "uuid-other", Map.of(), Map.of(), "gen-other");
        main.getExtensionSyncStates().put("Beta.Склад", sklad);
        main.getExtensionSyncStates().put("Beta.Другое", other);
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store, main));

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(extensionProject("Beta.Склад"), ref, false);

        assertTrue(String.valueOf(recheck.reason()), recheck.done());
        assertSame("состояние конфигурации то же", main, SyncV2Fakes.stateOf(delegate.holder));
        assertEquals("gen-main", main.getGenerationId());
        SyncV2Fakes.FakeState reset = main.getExtensionSyncStates().get("Beta.Склад");
        assertEquals("", reset.getGenerationId());
        assertEquals(7L, reset.getTimestamp());
        assertEquals("uuid-sklad", reset.getConfigurationUUID());
        assertEquals(sklad.getEdtResourceMetadata(), reset.getEdtResourceMetadata());
        assertEquals(sklad.getPlatformResourceVersions(), reset.getPlatformResourceVersions());
        assertSame(other, main.getExtensionSyncStates().get("Beta.Другое"));
        assertEquals(List.of(main), store.written);
    }

    /** Идентификатор уже пуст — сбрасывать нечего: без записи, но успех (и замок снят). */
    @Test
    public void forceRecheck_blankGeneration_noWrite_done() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.FakeState state = new SyncV2Fakes.FakeState(42L, "uuid", Map.of(), Map.of(), "");
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store, state));

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(configurationProject("Beta"), ref, false);

        assertTrue(String.valueOf(recheck.reason()), recheck.done());
        assertSame(state, SyncV2Fakes.stateOf(delegate.holder));
        assertTrue(store.written.isEmpty());
        assertEquals("unlockInfobaseState", delegate.calls.get(delegate.calls.size() - 1));
    }

    /** fullReload: удаляется записанный ConfigDumpInfo именно этой пары — расширения или конфигурации. */
    @Test
    public void forceRecheck_fullReload_deletesRecordedDumpInfo_ofThatPairOnly() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        Files.writeString(store.getMainPlatformResourceVersionsPath(), "<ConfigDumpInfo/>");
        Path extensionDump = store.getExtensionPlatformResourceVersionsPath("Beta.Склад");
        Files.createDirectories(extensionDump.getParent());
        Files.writeString(extensionDump, "<ConfigDumpInfo/>");
        SyncV2Fakes.FakeState state = new SyncV2Fakes.FakeState(42L, "uuid", Map.of(), Map.of(), "gen");
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store, state));
        SyncV2 v2 = new SyncV2(() -> SyncV2Fakes.stateManager(delegate));

        assertTrue(v2.forceRecheck(extensionProject("Beta.Склад"), ref, true).done());
        assertFalse(Files.exists(extensionDump));
        assertTrue(Files.exists(store.getMainPlatformResourceVersionsPath()));

        assertTrue(v2.forceRecheck(configurationProject("Beta"), ref, true).done());
        assertFalse(Files.exists(store.getMainPlatformResourceVersionsPath()));
    }

    /** Идёт другая синхронизация с этой базой — EDT не трогаем вовсе. */
    @Test
    public void forceRecheck_whileAnotherFlowIsActive_touchesNothing() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));
        IInfobaseSynchronizationStateManager sm = SyncV2Fakes.stateManager(delegate);
        when(sm.isFlowActive(ref)).thenReturn(true);

        SyncV2.Recheck recheck = new SyncV2(() -> sm).forceRecheck(configurationProject("Beta"), ref, true);

        assertFalse(recheck.done());
        assertTrue(recheck.reason(), recheck.reason().contains("другая синхронизация с этой базой идёт прямо сейчас"));
        assertTrue(delegate.calls.isEmpty());
        assertTrue(store.written.isEmpty());
    }

    /** Замок состояния базы не взят — не done, и снимать нечего (unlock владельца не проверяет). */
    @Test
    public void forceRecheck_lockFailure_notDone_noUnlock() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));
        delegate.lockFailure = new IllegalStateException("The target IB is blocked by another EDT synchronization flow");

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(configurationProject("Beta"), ref, false);

        assertFalse(recheck.done());
        assertTrue(recheck.reason(), recheck.reason().contains("blocked by another EDT synchronization flow"));
        assertTrue(delegate.calls.contains("lockInfobaseState"));
        assertFalse(delegate.calls.contains("unlockInfobaseState"));
        assertTrue(store.written.isEmpty());
    }

    /** Запись упала под замком — не done с причиной, замок всё равно снят. */
    @Test
    public void forceRecheck_writeFailure_notDone_unlocked() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        store.writeFailure = new IllegalStateException("диск только для чтения");
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(configurationProject("Beta"), ref, false);

        assertFalse(recheck.done());
        assertTrue(recheck.reason(), recheck.reason().contains("EDT не записала состояние синхронизации"));
        assertTrue(recheck.reason(), recheck.reason().contains("диск только для чтения"));
        assertEquals("unlockInfobaseState", delegate.calls.get(delegate.calls.size() - 1));
    }

    /** Нет внутреннего метода (другая версия EDT) — не done с причиной, ничего не брошено и не вызвано. */
    @Test
    public void forceRecheck_missingPrivateMethod_notDone() throws Exception {
        SyncV2Fakes.Delegate withoutInternals = mock(SyncV2Fakes.Delegate.class);

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(withoutInternals))
            .forceRecheck(configurationProject("Beta"), ref, false);

        assertFalse(recheck.done());
        assertTrue(recheck.reason(), recheck.reason().contains("в этой версии EDT нет внутреннего метода"));
        assertTrue(recheck.reason(), recheck.reason().contains("calculateStoreProject"));
        verifyZeroInteractions(withoutInternals);
    }

    /**
     * У держателя другой формы нет нужного поля (другая версия EDT) — не done с причиной, а замок базы даже не
     * берётся: все поиски членов — до замка.
     */
    @Test
    public void forceRecheck_missingHolderField_notDone_lockNeverTaken() throws Exception {
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(null);
        delegate.otherHolder = new Object();

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(configurationProject("Beta"), ref, false);

        assertFalse(recheck.done());
        assertTrue(recheck.reason(), recheck.reason().contains("в этой версии EDT нет внутреннего поля"));
        assertTrue(recheck.reason(), recheck.reason().contains("state"));
        assertFalse(delegate.calls.contains("lockInfobaseState"));
        assertFalse(delegate.calls.contains("unlockInfobaseState"));
    }

    /**
     * Fix round 7 (R7-4): замок не снялся — «не сделано» с причиной, хотя запись уже прошла (отчёт честный:
     * замок базы остался у EDT).
     */
    @Test
    public void forceRecheck_unlockFailure_notDone_withReason() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));
        delegate.unlockFailure = new IllegalStateException("замок уже снят другим потоком");

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(configurationProject("Beta"), ref, false);

        assertFalse(recheck.done());
        assertTrue(recheck.reason(), recheck.reason().contains("не удалось снять замок состояния синхронизации"));
        assertTrue(recheck.reason(), recheck.reason().contains("замок уже снят другим потоком"));
        assertEquals("запись до сбоя снятия замка прошла", 1, store.written.size());
    }

    /**
     * Fix round 7 (R7-4): вид проекта не определить ({@code CoreException} из {@code hasNature}) — «не сделано», и
     * EDT не тронута вовсе: ни замков, ни держателя (вид проекта спрашивается сразу после
     * {@code calculateStoreProject}).
     */
    @Test
    public void forceRecheck_natureUnknown_notDone_touchesNothing() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.FakeState state = new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen");
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store, state));
        IProject closed = project("Закрытый");
        when(closed.hasNature(EXTENSION_NATURE)).thenThrow(new CoreException(Status.error("проект закрыт")));

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate)).forceRecheck(closed, ref, true);

        assertFalse(recheck.done());
        assertTrue(recheck.reason(), recheck.reason().contains("не удалось определить вид проекта"));
        assertTrue(recheck.reason(), recheck.reason().contains("проект закрыт"));
        assertEquals(List.of("calculateStoreProject"), delegate.calls);
        assertSame(state, SyncV2Fakes.stateOf(delegate.holder));
        assertTrue(store.written.isEmpty());
    }

    /**
     * Fix round 7 (R7-4): у проекта расширения ещё нет записанного состояния, полная перезагрузка — сбрасывать
     * нечего (записи нет), а его ConfigDumpInfo, если лежит, удаляется.
     */
    @Test
    public void forceRecheck_extensionWithoutState_fullReload_deletesItsDumpInfo_noWrite() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        Path extensionDump = store.getExtensionPlatformResourceVersionsPath("Beta.Новое");
        Files.createDirectories(extensionDump.getParent());
        Files.writeString(extensionDump, "<ConfigDumpInfo/>");
        SyncV2Fakes.FakeState main = new SyncV2Fakes.FakeState(42L, "uuid-conf", Map.of(), Map.of(), "gen-main");
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store, main));

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(extensionProject("Beta.Новое"), ref, true);

        assertTrue(String.valueOf(recheck.reason()), recheck.done());
        assertFalse(Files.exists(extensionDump));
        assertTrue(store.written.isEmpty());
        assertEquals("gen-main", main.getGenerationId());
    }

    /**
     * Fix round 7 (R7-4): путь ConfigDumpInfo расширения спрашивается только для полной перезагрузки — у EDT этот
     * вызов создаёт каталог, а его сбой превращал бы уже сделанный сброс в «не сделано».
     */
    @Test
    public void forceRecheck_extension_withoutFullReload_neverResolvesDumpPath() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.FakeState main = new SyncV2Fakes.FakeState(42L, "uuid-conf", Map.of(), Map.of(), "gen-main");
        main.getExtensionSyncStates().put("Beta.Склад",
            new SyncV2Fakes.FakeState(7L, "uuid-sklad", Map.of(), Map.of(), "gen-sklad"));
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store, main));
        int before = store.extensionPathLookups;

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(extensionProject("Beta.Склад"), ref, false);

        assertTrue(String.valueOf(recheck.reason()), recheck.done());
        assertEquals("", main.getExtensionSyncStates().get("Beta.Склад").getGenerationId());
        assertEquals("без полной перезагрузки путь не спрашиваем", before, store.extensionPathLookups);
    }

    /**
     * Fix round 7 (R7-3): EDT хранит состояние проекта у ДРУГОГО проекта ({@code calculateStoreProject} отдаёт
     * родителя любого зависимого проекта, например проекта внешних отчётов и обработок), а это не расширение —
     * сброс задел бы состояние и ConfigDumpInfo родительской конфигурации. «Не сделано», EDT не тронута.
     */
    @Test
    public void forceRecheck_storedUnderAnotherProject_notExtension_notDone_touchesNothing() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        Files.writeString(store.getMainPlatformResourceVersionsPath(), "<ConfigDumpInfo/>");
        SyncV2Fakes.FakeState parentState = new SyncV2Fakes.FakeState(42L, "uuid-conf", Map.of(), Map.of(), "gen");
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store, parentState));
        delegate.storeProject = project("Beta");
        IProject externalObjects = project("Beta.ВнешниеОбработки");
        when(externalObjects.hasNature(EXTENSION_NATURE)).thenReturn(false);

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(externalObjects, ref, true);

        assertFalse(recheck.done());
        assertTrue(recheck.reason(), recheck.reason().contains("Beta.ВнешниеОбработки"));
        assertTrue(recheck.reason(), recheck.reason().contains("не проект конфигурации и не проект расширения"));
        assertEquals(List.of("calculateStoreProject"), delegate.calls);
        assertSame(parentState, SyncV2Fakes.stateOf(delegate.holder));
        assertEquals("gen", parentState.getGenerationId());
        assertTrue(store.written.isEmpty());
        assertTrue("ConfigDumpInfo родителя на месте", Files.exists(store.getMainPlatformResourceVersionsPath()));
    }

    /**
     * Fix round 8 (M2) / 9: ConfigDumpInfo отложен, а потом не снялся замок — «не сделано», но сведения о
     * переносе правдивы и доходят до вызывающего: без них отложенную копию нечем было бы вернуть.
     */
    @Test
    public void forceRecheck_dumpMovedThenUnlockFailed_notDone_butMoveReported() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        Files.writeString(store.getMainPlatformResourceVersionsPath(), "<ConfigDumpInfo/>");
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));
        delegate.unlockFailure = new IllegalStateException("замок уже снят другим потоком");

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(configurationProject("Beta"), ref, true);

        assertFalse(recheck.done());
        assertTrue("ConfigDumpInfo отложен — сведения правдивы", recheck.dumpInfoMoved());
        assertFalse(Files.exists(store.getMainPlatformResourceVersionsPath()));
        assertTrue(Files.exists(recheck.moved().aside()));
    }

    /** Полная перезагрузка — ConfigDumpInfo отложен; без неё — нет. */
    @Test
    public void forceRecheck_dumpInfoMoved_onlyWithFullReload() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        Files.writeString(store.getMainPlatformResourceVersionsPath(), "<ConfigDumpInfo/>");
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));
        SyncV2 v2 = new SyncV2(() -> SyncV2Fakes.stateManager(delegate));

        assertFalse(v2.forceRecheck(configurationProject("Beta"), ref, false).dumpInfoMoved());
        SyncV2.Recheck full = v2.forceRecheck(configurationProject("Beta"), ref, true);
        assertTrue(full.done());
        assertTrue(full.dumpInfoMoved());
    }

    /**
     * Fix round 9 (рекомендация 1): ConfigDumpInfo для полной перезагрузки не удаляется, а переносится рядом
     * ({@code <имя>.mcp-prev}, прежняя копия перезаписывается): EDT его не видит, а вернуть можно.
     */
    @Test
    public void forceRecheck_fullReload_movesDumpInfoAside_keepingContent() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        Path original = store.getMainPlatformResourceVersionsPath();
        Files.writeString(original, "<ConfigDumpInfo v=\"записанное\"/>");
        Files.writeString(original.resolveSibling(original.getFileName() + ".mcp-prev"), "устаревшая копия");
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(configurationProject("Beta"), ref, true);

        assertTrue(String.valueOf(recheck.reason()), recheck.done());
        assertEquals(original, recheck.moved().original());
        assertEquals(original.resolveSibling(original.getFileName() + ".mcp-prev"), recheck.moved().aside());
        assertFalse(Files.exists(original));
        assertEquals("<ConfigDumpInfo v=\"записанное\"/>", Files.readString(recheck.moved().aside()));
    }

    /** Fix round 9 (m3): записанного ConfigDumpInfo не было — откладывать нечего, и «перенесено» не сообщается. */
    @Test
    public void forceRecheck_fullReload_withoutRecordedDumpInfo_reportsNothingMoved() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));

        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(configurationProject("Beta"), ref, true);

        assertTrue(String.valueOf(recheck.reason()), recheck.done());
        assertFalse(recheck.dumpInfoMoved());
    }

    /** Отложили ConfigDumpInfo для полной перезагрузки: вызывающий получает и фейк-хранилище, и перенос. */
    private SyncV2.Recheck movedAside(SyncV2Fakes.FakeStore store, SyncV2Fakes.RecheckDelegate delegate)
            throws Exception {
        Files.writeString(store.getMainPlatformResourceVersionsPath(), "<ConfigDumpInfo v=\"записанное\"/>");
        SyncV2.Recheck recheck = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .forceRecheck(configurationProject("Beta"), ref, true);
        assertTrue(recheck.dumpInfoMoved());
        delegate.calls.clear();
        return recheck;
    }

    /** Рекомендация 1: полная перезагрузка прошла — отложенная копия удаляется. */
    @Test
    public void settleDumpInfo_fullReloadDone_discardsAside() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));
        SyncV2.Recheck recheck = movedAside(store, delegate);
        Files.writeString(store.getMainPlatformResourceVersionsPath(), "<ConfigDumpInfo v=\"новое от EDT\"/>");

        SyncV2.DumpInfoSettlement settled = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .settleDumpInfo(configurationProject("Beta"), ref, recheck, true);

        assertEquals(SyncV2.DumpInfoFate.DISCARDED, settled.fate());
        assertFalse(Files.exists(recheck.moved().aside()));
        assertEquals("<ConfigDumpInfo v=\"новое от EDT\"/>", Files.readString(store.getMainPlatformResourceVersionsPath()));
    }

    /** Рекомендация 1: полной перезагрузки не было — копия возвращается на место, под замками EDT. */
    @Test
    public void settleDumpInfo_noFullReload_restoresUnderLocks() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));
        SyncV2.Recheck recheck = movedAside(store, delegate);

        SyncV2.DumpInfoSettlement settled = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .settleDumpInfo(configurationProject("Beta"), ref, recheck, false);

        assertEquals(SyncV2.DumpInfoFate.RESTORED, settled.fate());
        assertEquals("<ConfigDumpInfo v=\"записанное\"/>", Files.readString(store.getMainPlatformResourceVersionsPath()));
        assertFalse(Files.exists(recheck.moved().aside()));
        assertTrue(delegate.calls.toString(), delegate.calls.contains("lockInfobaseState"));
        assertEquals("замок снят последним", "unlockInfobaseState", delegate.calls.get(delegate.calls.size() - 1));
    }

    /** Рекомендация 1: пока шёл забор, EDT записала свой ConfigDumpInfo — оставляем её, нашу копию удаляем. */
    @Test
    public void settleDumpInfo_edtWroteNewDumpInfo_keepsEdts_discardsOurs() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));
        SyncV2.Recheck recheck = movedAside(store, delegate);
        Files.writeString(store.getMainPlatformResourceVersionsPath(), "<ConfigDumpInfo v=\"новое от EDT\"/>");

        SyncV2.DumpInfoSettlement settled = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .settleDumpInfo(configurationProject("Beta"), ref, recheck, false);

        assertEquals(SyncV2.DumpInfoFate.KEPT_EDT_COPY, settled.fate());
        assertEquals("<ConfigDumpInfo v=\"новое от EDT\"/>", Files.readString(store.getMainPlatformResourceVersionsPath()));
        assertFalse(Files.exists(recheck.moved().aside()));
    }

    /** Рекомендация 1: вернуть не удалось (замок базы занят) — причина, отложенная копия цела. */
    @Test
    public void settleDumpInfo_restoreFails_reportsReason_keepsAside() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));
        SyncV2.Recheck recheck = movedAside(store, delegate);
        delegate.lockFailure = new IllegalStateException("The target IB is blocked by another EDT synchronization flow");

        SyncV2.DumpInfoSettlement settled = new SyncV2(() -> SyncV2Fakes.stateManager(delegate))
            .settleDumpInfo(configurationProject("Beta"), ref, recheck, false);

        assertEquals(SyncV2.DumpInfoFate.RESTORE_FAILED, settled.fate());
        assertTrue(settled.reason(), settled.reason().contains("blocked by another EDT synchronization flow"));
        assertTrue("копия не потеряна", Files.exists(recheck.moved().aside()));
    }

    /** Ничего не откладывалось — и возвращать нечего. */
    @Test
    public void settleDumpInfo_nothingMoved_none() throws Exception {
        SyncV2.DumpInfoSettlement settled = new SyncV2(() -> null)
            .settleDumpInfo(configurationProject("Beta"), ref, new SyncV2.Recheck(true, null), false);

        assertEquals(SyncV2.DumpInfoFate.NONE, settled.fate());
    }

    /**
     * Fix round 9 (m4): спросить «идёт ли синхронизация» не удалось — ждать нечем, но и считать «можно» нельзя
     * (fail-closed): причина, по которой сеанс не закрываем, сброс не делаем и не отмечаем.
     */
    @Test
    public void awaitNoActiveFlow_checkItselfFails_failClosedWithReason() {
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        when(sm.isFlowActive(ref)).thenThrow(new IllegalStateException("состояние базы не читается"));

        String busy = new SyncV2(() -> sm).awaitNoActiveFlow(ref, new NullProgressMonitor(), Duration.ofSeconds(10),
            Duration.ofMillis(10));

        assertNotNull(busy);
        assertTrue(busy, busy.contains("не удалось проверить, идёт ли синхронизация базы"));
        assertTrue(busy, busy.contains("состояние базы не читается"));
    }

    /**
     * Fix round 8 (L9): EDT синхронизирует базу сама (фоновая проверка проекта после запуска) — ждём спящим опросом,
     * а не отказываем: проверка закончилась через несколько опросов — «можно».
     */
    @Test(timeout = 20_000)
    public void awaitNoActiveFlow_flowEndsAfterPolls_returnsNull() {
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        when(sm.isFlowActive(ref)).thenReturn(true, true, false);

        String busy = new SyncV2(() -> sm).awaitNoActiveFlow(ref, new NullProgressMonitor(), Duration.ofSeconds(10),
            Duration.ofMillis(10));

        assertNull(busy);
        verify(sm, times(3)).isFlowActive(ref);
    }

    /** Синхронизация идёт дольше предела — причина «EDT синхронизирует эту базу дольше N с». */
    @Test(timeout = 20_000)
    public void awaitNoActiveFlow_stillActiveAtBound_returnsReason() {
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        when(sm.isFlowActive(ref)).thenReturn(true);
        long t0 = System.nanoTime();

        String busy = new SyncV2(() -> sm).awaitNoActiveFlow(ref, new NullProgressMonitor(), Duration.ofMillis(200),
            Duration.ofMillis(20));

        assertNotNull(busy);
        assertTrue(busy, busy.contains("EDT синхронизирует эту базу дольше"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5000);
    }

    /** Отмена задания во время ожидания — отмена. */
    @Test(timeout = 20_000)
    public void awaitNoActiveFlow_cancelled_propagatesCancellation() {
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class);
        when(sm.isFlowActive(ref)).thenReturn(true);
        IProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        try {
            new SyncV2(() -> sm).awaitNoActiveFlow(ref, monitor, Duration.ofSeconds(10), Duration.ofMillis(10));
            fail("expected OperationCanceledException");
        } catch (OperationCanceledException expected) {
            // ожидание отменено вместе с заданием
        }
    }

    /** Общий статический UNDEFINED («состояния нет») не трогается никогда — даже если в его карте что-то лежит. */
    @Test
    public void forceRecheck_sharedUndefinedState_neverMutated() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.FakeState stray = new SyncV2Fakes.FakeState(3L, "uuid", Map.of(), Map.of(), "gen-stray");
        SyncV2Fakes.FakeState.UNDEFINED.getExtensionSyncStates().put("Beta.Склад", stray);
        try {
            SyncV2Fakes.RecheckDelegate delegate = new SyncV2Fakes.RecheckDelegate(
                SyncV2Fakes.holder(store, SyncV2Fakes.FakeState.UNDEFINED));
            SyncV2 v2 = new SyncV2(() -> SyncV2Fakes.stateManager(delegate));

            assertTrue(v2.forceRecheck(extensionProject("Beta.Склад"), ref, false).done());
            assertTrue(v2.forceRecheck(configurationProject("Beta"), ref, false).done());

            assertSame(stray, SyncV2Fakes.FakeState.UNDEFINED.getExtensionSyncStates().get("Beta.Склад"));
            assertSame(SyncV2Fakes.FakeState.UNDEFINED, SyncV2Fakes.stateOf(delegate.holder));
            assertTrue(store.written.isEmpty());
        } finally {
            SyncV2Fakes.FakeState.UNDEFINED.getExtensionSyncStates().remove("Beta.Склад");
        }
    }

    /**
     * Проект расширения, у родителя которого нет записанного состояния («сирота»): {@code calculateStoreProject}
     * отдаёт сам проект, и делегат EDT записал бы общий статический {@code InfobaseSyncState.UNDEFINED}.
     * Без состояния синхронизации после обновления EDT не трогаем.
     */
    @Test
    public void markSynchronized_withoutSyncInfo_doesNotCallEdt() {
        SyncV2Fakes.Delegate delegate = mock(SyncV2Fakes.Delegate.class);
        IProject orphan = project("Сирота");
        IInfobaseSynchronizationStateManager sm = SyncV2Fakes.stateManager(delegate);
        when(sm.hasSynchronizationInfo(orphan, ref)).thenReturn(false);

        SyncV2.Marking marking = new SyncV2(() -> sm).markSynchronized(orphan, ref);

        assertFalse(marking.marked());
        assertTrue(marking.reason(), marking.reason().contains("EDT не записала состояние синхронизации"));
        verifyZeroInteractions(delegate);
    }

    /**
     * {@code lockInfobaseState}/{@code unlockInfobaseState} внутри {@code forceEdtSynchronization} владельца не
     * проверяют — во время чужой синхронизации с базой можно снять её замок. Идёт синхронизация — EDT не трогаем.
     */
    @Test
    public void markSynchronized_whileAnotherFlowIsActive_doesNotCallEdt() {
        SyncV2Fakes.Delegate delegate = mock(SyncV2Fakes.Delegate.class);
        IInfobaseSynchronizationStateManager sm = SyncV2Fakes.stateManager(delegate);
        when(sm.isFlowActive(ref)).thenReturn(true);

        SyncV2.Marking marking = new SyncV2(() -> sm).markSynchronized(project("Demo"), ref);

        assertFalse(marking.marked());
        assertTrue(marking.reason(), marking.reason().contains("другая синхронизация с этой базой идёт прямо сейчас"));
        verifyZeroInteractions(delegate);
    }
}
