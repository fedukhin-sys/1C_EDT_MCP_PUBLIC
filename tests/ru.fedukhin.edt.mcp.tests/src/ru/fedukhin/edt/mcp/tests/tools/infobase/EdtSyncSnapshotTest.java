package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.resource.EdtResourceMetadata;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.eclipse.core.resources.IProject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncSnapshot;

/**
 * Чтение снимка синхронизации EDT для {@code get_infobase_sync_state}: путь {@code isProjectDirty} (только поиск
 * держателя), копия карт под монитором делегата, снимок на диске через {@code readState()} хранилища.
 */
public class EdtSyncSnapshotTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final InfobaseReference ref = mock(InfobaseReference.class);

    private static IProject project(String name) {
        IProject p = mock(IProject.class);
        when(p.getName()).thenReturn(name);
        return p;
    }

    static EdtResourceMetadata meta(int... signature) {
        return new EdtResourceMetadata(SignatureDiffTest.sig(signature), UUID.randomUUID());
    }

    private SyncV2Fakes.FakeStore store() throws Exception {
        Path dir = tmp.newFolder().toPath();
        return new SyncV2Fakes.FakeStore(dir.resolve("ConfigDumpInfo.xml"), dir.resolve("extensions"));
    }

    @Test
    public void memoryAndDisk_byProjectName_onlyFindIsCalled() throws Exception {
        SyncV2Fakes.FakeState main = new SyncV2Fakes.FakeState(1_700_000_000_000L, "uuid",
            Map.of("src/a.bsl", meta(1)), Map.of(), "gen");
        main.getExtensionSyncStates().put("Demo.Склад",
            new SyncV2Fakes.FakeState(5L, "uuid-ext", Map.of("src/x.bsl", meta(2)), Map.of(), "gen-ext"));
        SyncV2Fakes.FakeStore store = store();
        store.persisted = new SyncV2Fakes.FakeState(7L, "uuid", Map.of("src/a.bsl", meta(9)), Map.of(), "gen");
        SyncV2Fakes.SnapshotDelegate delegate = new SyncV2Fakes.SnapshotDelegate(SyncV2Fakes.holder(store, main));

        EdtSyncSnapshot.Read read = EdtSyncSnapshot.read(delegate, project("Demo"), ref,
            List.of("Demo.Склад", "Demo.Нет"), true);

        assertNull(read.failure());
        assertArrayEquals(new byte[] {1}, read.memory().get("Demo").files().get("src/a.bsl"));
        assertEquals(1_700_000_000_000L, read.memory().get("Demo").timestamp());
        assertArrayEquals(new byte[] {2}, read.memory().get("Demo.Склад").files().get("src/x.bsl"));
        assertFalse("записи нет — ключа нет", read.memory().containsKey("Demo.Нет"));
        assertNull(read.diskFailure());
        assertArrayEquals(new byte[] {9}, read.disk().get("Demo").files().get("src/a.bsl"));
        assertFalse(read.disk().containsKey("Demo.Склад"));
        assertEquals(List.of("calculateStoreProject", "findProjectInfobaseSynchronizationStateHolder"),
            delegate.calls);
    }

    /** Метаданные ресурса, которые запоминают, держится ли монитор делегата, когда у них берут подпись. */
    public static final class LockProbeMetadata {
        private final BooleanSupplier lockHeld;
        private final List<Boolean> seen;

        LockProbeMetadata(BooleanSupplier lockHeld, List<Boolean> seen) {
            this.lockHeld = lockHeld;
            this.seen = seen;
        }

        public byte[] getSignature() {
            seen.add(lockHeld.getAsBoolean());
            return new byte[] {1};
        }
    }

    /** Состояние, которое запоминает, держится ли монитор делегата, когда у него берут карты. */
    public static final class LockProbeState {
        private final BooleanSupplier lockHeld;
        public final List<Boolean> seen = new ArrayList<>();
        public final List<Boolean> signaturesSeen = new ArrayList<>();

        LockProbeState(BooleanSupplier lockHeld) {
            this.lockHeld = lockHeld;
        }

        public Map<String, Object> getEdtResourceMetadata() {
            seen.add(lockHeld.getAsBoolean());
            return Map.of("src/a.bsl", new LockProbeMetadata(lockHeld, signaturesSeen));
        }

        public long getTimestamp() {
            return 1L;
        }

        public Map<String, Object> getExtensionSyncStates() {
            seen.add(lockHeld.getAsBoolean());
            return Map.of();
        }
    }

    /** Держатель произвольной формы: те же имена полей, что у EDT. */
    static final class ProbeHolder {
        public volatile Object state;
        public final Object synchronizationStore = null;
    }

    /** Карты EDT копируются под монитором делегата, а подписи разбираются уже без него. */
    @Test
    public void maps_copiedUnderDelegateLock_signaturesReadOutside() {
        ProbeHolder holder = new ProbeHolder();
        SyncV2Fakes.SnapshotDelegate delegate = new SyncV2Fakes.SnapshotDelegate(holder);
        LockProbeState state = new LockProbeState(delegate::holdsLock);
        holder.state = state;

        EdtSyncSnapshot.Read read = EdtSyncSnapshot.read(delegate, project("Demo"), ref, List.of(), false);

        assertNotNull(read.failure(), read.memory());
        assertArrayEquals(new byte[] {1}, read.memory().get("Demo").files().get("src/a.bsl"));
        assertEquals("геттеры — под монитором", List.of(true, true), state.seen);
        assertEquals("подписи — без монитора", List.of(false), state.signaturesSeen);
        assertFalse(delegate.holdsLock());
    }

    @Test
    public void noHolder_explainsAndNeverCreatesOne() {
        SyncV2Fakes.SnapshotDelegate delegate = new SyncV2Fakes.SnapshotDelegate(null);

        EdtSyncSnapshot.Read read = EdtSyncSnapshot.read(delegate, project("Demo"), ref, List.of(), true);

        assertNull(read.memory());
        assertTrue(read.failure(), read.failure().contains("нет состояния синхронизации"));
        assertNull(read.disk());
        assertNotNull(read.diskFailure());
        assertFalse(delegate.calls.contains("findOrCreateProjectInfobaseSynchronizationStateHolder"));
    }

    @Test
    public void undefinedState_isNoState_diskStillRead() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.SnapshotDelegate delegate = new SyncV2Fakes.SnapshotDelegate(
            SyncV2Fakes.holder(store, SyncV2Fakes.FakeState.UNDEFINED));

        EdtSyncSnapshot.Read read = EdtSyncSnapshot.read(delegate, project("Demo"), ref, List.of(), true);

        assertNull(read.memory());
        assertTrue(read.failure(), read.failure().contains("UNDEFINED"));
        assertNull(read.disk());
        assertTrue(read.diskFailure(), read.diskFailure().contains("на диске снимка нет"));
        assertEquals(1, store.readCalls);
    }

    @Test
    public void diskReadFailure_keepsMemory() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        store.readFailure = new IllegalStateException("битый index.idx");
        SyncV2Fakes.SnapshotDelegate delegate = new SyncV2Fakes.SnapshotDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of("src/a.bsl", meta(1)), Map.of(), "gen")));

        EdtSyncSnapshot.Read read = EdtSyncSnapshot.read(delegate, project("Demo"), ref, List.of(), true);

        assertNotNull(read.memory());
        assertTrue(read.diskFailure(), read.diskFailure().contains("битый index.idx"));
    }

    @Test
    public void withoutDisk_storeIsNotRead() throws Exception {
        SyncV2Fakes.FakeStore store = store();
        SyncV2Fakes.SnapshotDelegate delegate = new SyncV2Fakes.SnapshotDelegate(SyncV2Fakes.holder(store,
            new SyncV2Fakes.FakeState(1L, "uuid", Map.of(), Map.of(), "gen")));

        EdtSyncSnapshot.Read read = EdtSyncSnapshot.read(delegate, project("Demo"), ref, List.of(), false);

        assertNull(read.disk());
        assertNull(read.diskFailure());
        assertEquals(0, store.readCalls);
    }

    @Test
    public void missingPrivateMember_reasonNamesIt() {
        EdtSyncSnapshot.Read read = EdtSyncSnapshot.read(new Object(), project("Demo"), ref, List.of(), true);

        assertNull(read.memory());
        assertTrue(read.failure(), read.failure().contains("нет внутреннего метода"));
        assertTrue(read.failure(), read.failure().contains("calculateStoreProject"));
    }

    @Test
    public void signatures_nullValueKept_nonStringKeySkipped() throws Exception {
        Map<Object, Object> metadata = new HashMap<>();
        metadata.put("src/a.bsl", meta(3));
        metadata.put("src/empty.bsl", null);
        metadata.put(42, meta(4));

        Map<String, byte[]> signatures = EdtSyncSnapshot.signatures(metadata);

        assertEquals(2, signatures.size());
        assertArrayEquals(new byte[] {3}, signatures.get("src/a.bsl"));
        assertTrue(signatures.containsKey("src/empty.bsl"));
        assertNull(signatures.get("src/empty.bsl"));
    }
}
