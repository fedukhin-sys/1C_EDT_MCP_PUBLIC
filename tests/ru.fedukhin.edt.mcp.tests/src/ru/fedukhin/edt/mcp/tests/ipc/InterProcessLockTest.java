package ru.fedukhin.edt.mcp.tests.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.ipc.InterProcessLock;
import ru.fedukhin.edt.mcp.core.ipc.LockTimeoutException;
import ru.fedukhin.edt.mcp.core.ipc.McpHome;

/**
 * Замок разводит монопольные операции нескольких инстанций EDT по одной
 * информационной базе. Ключевое требование к отказу — он обязан называть
 * держателя, иначе пользователь видит бессмысленный таймаут.
 */
public class InterProcessLockTest {

    private Path home;
    private String saved;

    @Before
    public void up() throws Exception {
        saved = System.getProperty(McpHome.SYS_PROP);
        home = Files.createTempDirectory("edt-mcp-lock-");
        System.setProperty(McpHome.SYS_PROP, home.toString());
    }

    @After
    public void down() throws Exception {
        if (saved == null) System.clearProperty(McpHome.SYS_PROP);
        else System.setProperty(McpHome.SYS_PROP, saved);
        deleteTree(home);
    }

    @Test
    public void acquire_thenRelease_allowsSecondAcquire() throws Exception {
        try (InterProcessLock l = InterProcessLock.acquire(
                "ib:DemoBase", "deploy_project pid=1", Duration.ofSeconds(1))) {
            assertTrue(l != null);
        }
        try (InterProcessLock l2 = InterProcessLock.acquire(
                "ib:DemoBase", "run_tests pid=1", Duration.ofSeconds(1))) {
            assertTrue(l2 != null);
        }
    }

    /**
     * Повторный захват того же ключа не должен падать OverlappingFileLockException:
     * FileLock эксклюзивен на уровне JVM, поэтому перед ним стоит семафор. Именно
     * семафор, а не ReentrantLock — иначе тот же поток прошёл бы насквозь.
     */
    @Test
    public void secondAcquireInSameJvm_timesOutInsteadOfCrashing() throws Exception {
        try (InterProcessLock held = InterProcessLock.acquire(
                "ib:Same", "first-holder", Duration.ofSeconds(1))) {
            try {
                InterProcessLock.acquire("ib:Same", "second-holder", Duration.ofMillis(300));
                fail("ожидался LockTimeoutException");
            } catch (LockTimeoutException e) {
                assertTrue("отказ должен называть держателя, было: " + e.getMessage(),
                        e.getMessage().contains("first-holder"));
                assertTrue("отказ должен называть запросившего, было: " + e.getMessage(),
                        e.getMessage().contains("second-holder"));
            }
        }
    }

    @Test
    public void holderOf_readsMetadataWhileLocked() throws Exception {
        try (InterProcessLock held = InterProcessLock.acquire(
                "ib:Meta", "deploy_project ws=Demo", Duration.ofSeconds(1))) {
            assertEquals("deploy_project ws=Demo", InterProcessLock.holderOf("ib:Meta").orElse(null));
        }
    }

    @Test
    public void holderOf_onUnknownKey_isEmpty() {
        assertTrue(InterProcessLock.holderOf("ib:НикогдаНеБрали").isEmpty());
    }

    /** Разные ключи не мешают друг другу — иначе замок сериализовал бы всю машину. */
    @Test
    public void differentKeys_doNotBlockEachOther() throws Exception {
        try (InterProcessLock a = InterProcessLock.acquire("ib:A", "one", Duration.ofSeconds(1));
             InterProcessLock b = InterProcessLock.acquire("ib:B", "two", Duration.ofSeconds(1))) {
            assertEquals("one", InterProcessLock.holderOf("ib:A").orElse(null));
            assertEquals("two", InterProcessLock.holderOf("ib:B").orElse(null));
        }
    }

    /** Замок отпускается и после исключения внутри try-with-resources. */
    @Test
    public void close_releasesEvenAfterBodyThrows() throws Exception {
        try (InterProcessLock l = InterProcessLock.acquire("ib:Boom", "holder", Duration.ofSeconds(1))) {
            throw new IllegalStateException("нарочно");
        } catch (IllegalStateException expected) {
            // проглатываем: проверяем только, что замок отпущен
        }
        try (InterProcessLock again = InterProcessLock.acquire(
                "ib:Boom", "next", Duration.ofMillis(500))) {
            assertEquals("next", InterProcessLock.holderOf("ib:Boom").orElse(null));
        }
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
