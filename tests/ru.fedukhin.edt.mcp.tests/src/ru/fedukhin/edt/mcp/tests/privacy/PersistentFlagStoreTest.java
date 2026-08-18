package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.ipc.McpHome;
import ru.fedukhin.edt.mcp.core.privacy.InfobaseFlagStore;
import ru.fedukhin.edt.mcp.core.privacy.PersistentFlagStore;

/**
 * Флаг «в базе есть реальные ПДн» обязан действовать во всех инстанциях EDT.
 * Пока он жил в памяти процесса, set_infobase_pii_flag работал в одной сессии
 * из N, а в остальных оставался fail-closed дефолт.
 */
public class PersistentFlagStoreTest {

    private Path home;
    private String saved;

    @Before
    public void up() throws Exception {
        saved = System.getProperty(McpHome.SYS_PROP);
        home = Files.createTempDirectory("edt-mcp-flags-");
        System.setProperty(McpHome.SYS_PROP, home.toString());
    }

    @After
    public void down() throws Exception {
        if (saved == null) System.clearProperty(McpHome.SYS_PROP);
        else System.setProperty(McpHome.SYS_PROP, saved);
        deleteTree(home);
    }

    @Test
    public void flagSurvivesNewInstance() {
        new PersistentFlagStore().setFlag("Demo", false);

        assertFalse("флаг должен читаться другим экземпляром — он в файле, а не в памяти",
                new PersistentFlagStore().containsRealPersonalData("Demo"));
    }

    @Test
    public void defaultIsFailClosed() {
        assertTrue("неизвестная база считается содержащей ПДн",
                new PersistentFlagStore().containsRealPersonalData("Неизвестная"));
        assertTrue("null тоже fail-closed",
                new PersistentFlagStore().containsRealPersonalData(null));
    }

    @Test
    public void flagCanBeSetBackToTrue() {
        PersistentFlagStore store = new PersistentFlagStore();
        store.setFlag("Demo", false);
        store.setFlag("Demo", true);

        assertTrue(new PersistentFlagStore().containsRealPersonalData("Demo"));
    }

    /** Битый файл не должен ослаблять защиту: считаем, что флагов нет. */
    @Test
    public void corruptedFile_fallsBackToFailClosed() throws Exception {
        new PersistentFlagStore().setFlag("Demo", false);
        Path f = McpHome.privacy().resolve("infobase-flags.json");
        Files.write(f, "{не json".getBytes(StandardCharsets.UTF_8));

        assertTrue(new PersistentFlagStore().containsRealPersonalData("Demo"));
    }

    /** Обёртка InfobaseFlagStore должна прозрачно делегировать в файловое хранилище. */
    @Test
    public void infobaseFlagStore_delegatesToPersistent() {
        InfobaseFlagStore store = new InfobaseFlagStore(new PersistentFlagStore());
        store.setFlag("Demo", false);

        assertFalse(store.containsRealPersonalData("Demo"));
        assertFalse("и видно новому экземпляру обёртки",
                new InfobaseFlagStore(new PersistentFlagStore()).containsRealPersonalData("Demo"));
        assertTrue(store.containsRealPersonalData("Другая"));
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException ignored) { /* best-effort */ }
            });
        }
    }
}
