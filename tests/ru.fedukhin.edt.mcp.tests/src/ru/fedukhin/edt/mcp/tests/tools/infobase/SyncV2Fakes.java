package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.eclipse.core.resources.IProject;

/**
 * Заменители внутренних классов EDT для {@code SyncV2.markSynchronized}: у сервиса синхронизации EDT
 * ({@code InfobaseSynchronizationStateManager}) есть публичный {@code getDelegate()}, у делегата
 * ({@code InfobaseSynchronizationStateManagerDelegate}) — публичный
 * {@code forceEdtSynchronization(InfobaseReference, IProject)}; в API их нет, код зовёт их рефлексией.
 */
public final class SyncV2Fakes {

    private SyncV2Fakes() {}

    /** Публичный {@code getDelegate()}, как у {@code InfobaseSynchronizationStateManager}. */
    public interface DelegateAccess {
        Object getDelegate();
    }

    /** Публичный {@code forceEdtSynchronization}, как у {@code InfobaseSynchronizationStateManagerDelegate}. */
    public static class Delegate {
        public void forceEdtSynchronization(InfobaseReference infobase, IProject project) {
            // поведение задаёт мок
        }
    }

    // ---- F4 (fix round 6): формы внутренних классов EDT для SyncV2.forceRecheck (по байткоду dt.platform.services.core 23) ----

    /** Как {@code InfobaseSyncState}: публичный final-класс, публичный конструктор и геттеры, изменяемая карта расширений. */
    public static final class FakeState {
        /** Как {@code InfobaseSyncState.UNDEFINED} — общий статический экземпляр, трогать нельзя. */
        public static final FakeState UNDEFINED = new FakeState(0L, "", Map.of(), Map.of(), "");

        private final long timestamp;
        private final String configurationUUID;
        private final Map<String, Object> edtResourceMetadata;
        private final Map<String, String> platformResourceVersions;
        private final String generationId;
        private final Map<String, FakeState> extensionSyncStates = new HashMap<>();

        public FakeState(long timestamp, String configurationUUID, Map<String, Object> edtResourceMetadata,
                         Map<String, String> platformResourceVersions, String generationId) {
            this.timestamp = timestamp;
            this.configurationUUID = configurationUUID;
            this.edtResourceMetadata = new HashMap<>(edtResourceMetadata);
            this.platformResourceVersions = new HashMap<>(platformResourceVersions);
            this.generationId = generationId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getConfigurationUUID() {
            return configurationUUID;
        }

        public Map<String, Object> getEdtResourceMetadata() {
            return edtResourceMetadata;
        }

        public Map<String, String> getPlatformResourceVersions() {
            return platformResourceVersions;
        }

        public String getGenerationId() {
            return generationId;
        }

        public Map<String, FakeState> getExtensionSyncStates() {
            return extensionSyncStates;
        }
    }

    /** Как {@code InfobaseSynchronizationStateStore}: запись состояния и пути записанных ConfigDumpInfo. */
    public static final class FakeStore {
        public final List<FakeState> written = new ArrayList<>();
        /** Что было верно в момент каждой записи (например, «под обоими замками»). */
        public final List<Boolean> writtenUnderLocks = new ArrayList<>();
        public BooleanSupplier locksHeld = () -> true;
        public RuntimeException writeFailure;
        /**
         * Сколько раз спросили путь ConfigDumpInfo расширения: у EDT этот вызов создаёт каталог
         * {@code <store>/ext/<проект>} (fix round 7, R7-4) — без полной перезагрузки его не зовём.
         */
        public int extensionPathLookups;
        private final Path main;
        private final Path extensions;

        public FakeStore(Path main, Path extensions) {
            this.main = main;
            this.extensions = extensions;
        }

        public void writeEdtSyncrhonizationState(FakeState state) {
            if (writeFailure != null) throw writeFailure;
            written.add(state);
            writtenUnderLocks.add(locksHeld.getAsBoolean());
        }

        public Path getMainPlatformResourceVersionsPath() {
            return main;
        }

        public Path getExtensionPlatformResourceVersionsPath(String extensionProject) {
            extensionPathLookups++;
            return extensions.resolve(extensionProject + ".xml");
        }

        /** Что вернёт {@code readState()} — снимок «на диске»; {@code null} — снимка нет. */
        public FakeState persisted;
        public RuntimeException readFailure;
        public int readCalls;

        public FakeState readState() {
            readCalls++;
            if (readFailure != null) throw readFailure;
            return persisted;
        }
    }

    /** Как {@code …Delegate$ProjectInfobaseSynchronizationStateHolder}: пакетный класс с публичными полями. */
    static final class FakeHolder {
        public volatile FakeState state;
        public final FakeStore synchronizationStore;

        FakeHolder(FakeStore store, FakeState state) {
            this.synchronizationStore = store;
            this.state = state;
        }
    }

    public static FakeHolder holder(FakeStore store, FakeState state) {
        return new FakeHolder(store, state);
    }

    public static FakeState stateOf(FakeHolder holder) {
        return holder.state;
    }

    /**
     * Как {@code InfobaseSynchronizationStateManagerDelegate}: те же {@code private}-методы и поле {@code lock};
     * вызовы записываются по порядку.
     */
    public static final class RecheckDelegate {
        private final Object lock = new Object();
        public final List<String> calls = new ArrayList<>();
        public FakeHolder holder;
        /** Не {@code null} — отдаётся вместо {@link #holder} (держатель другой формы, как в другой версии EDT). */
        public Object otherHolder;
        public IProject storeProject;
        public RuntimeException lockFailure;
        public RuntimeException unlockFailure;

        public RecheckDelegate(FakeHolder holder) {
            this.holder = holder;
        }

        /** Сейчас держатся и замок состояния базы, и монитор {@code lock} делегата. */
        public boolean locksHeld() {
            return Thread.holdsLock(lock) && calls.contains("lockInfobaseState") && !calls.contains("unlockInfobaseState");
        }

        private IProject calculateStoreProject(IProject project) {
            calls.add("calculateStoreProject");
            return storeProject != null ? storeProject : project;
        }

        private void initLockStatesIfAbsent(IProject project, InfobaseReference infobase) {
            calls.add("initLockStatesIfAbsent");
        }

        private Object findOrCreateProjectInfobaseSynchronizationStateHolder(IProject project,
                                                                             InfobaseReference infobase) {
            calls.add("findOrCreateProjectInfobaseSynchronizationStateHolder");
            return otherHolder != null ? otherHolder : holder;
        }

        private void lockInfobaseState(InfobaseReference infobase) {
            calls.add("lockInfobaseState");
            if (lockFailure != null) throw lockFailure;
        }

        private void unlockInfobaseState(InfobaseReference infobase) {
            calls.add("unlockInfobaseState");
            if (unlockFailure != null) throw unlockFailure;
        }
    }

    /**
     * Мок сервиса синхронизации v2, у которого есть {@code getDelegate()}, отдающий {@code delegate}. По
     * умолчанию состояние синхронизации у любого проекта записано ({@code hasSynchronizationInfo = true}) и
     * другой синхронизации нет ({@code isFlowActive = false}) — отметке ничто не мешает.
     */
    public static IInfobaseSynchronizationStateManager stateManager(Object delegate) {
        IInfobaseSynchronizationStateManager sm = mock(IInfobaseSynchronizationStateManager.class,
            withSettings().extraInterfaces(DelegateAccess.class));
        when(((DelegateAccess) sm).getDelegate()).thenReturn(delegate);
        when(sm.hasSynchronizationInfo(any(IProject.class), any())).thenReturn(true);
        return sm;
    }

    // ---- get_infobase_sync_state: чтение снимка (EdtSyncSnapshot) ----

    /**
     * Как делегат для ЧТЕНИЯ снимка: {@code calculateStoreProject}, {@code findProjectInfobaseSynchronizationStateHolder}
     * (отдаёт {@code Optional}, как у EDT) и поле {@code lock}. {@code findOrCreate…} тоже есть — чтобы тест поймал
     * вызов: созданный держатель изменил бы ответ {@code isProjectDirty}.
     */
    public static final class SnapshotDelegate {
        private final Object lock = new Object();
        public final List<String> calls = new ArrayList<>();
        /** Держатель любой формы (у EDT — пакетный класс с полями {@code state}, {@code synchronizationStore}). */
        public Object holder;
        public IProject storeProject;

        public SnapshotDelegate(Object holder) {
            this.holder = holder;
        }

        public boolean holdsLock() {
            return Thread.holdsLock(lock);
        }

        private IProject calculateStoreProject(IProject project) {
            calls.add("calculateStoreProject");
            return storeProject != null ? storeProject : project;
        }

        private Optional<Object> findProjectInfobaseSynchronizationStateHolder(IProject project,
                                                                               InfobaseReference infobase) {
            calls.add("findProjectInfobaseSynchronizationStateHolder");
            return Optional.ofNullable(holder);
        }

        private Object findOrCreateProjectInfobaseSynchronizationStateHolder(IProject project,
                                                                            InfobaseReference infobase) {
            calls.add("findOrCreateProjectInfobaseSynchronizationStateHolder");
            return holder;
        }
    }
}
