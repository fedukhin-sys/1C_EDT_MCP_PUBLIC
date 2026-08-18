package ru.fedukhin.edt.mcp.tests.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.ipc.InstanceBeacon;
import ru.fedukhin.edt.mcp.core.ipc.InstanceRecord;
import ru.fedukhin.edt.mcp.core.ipc.McpHome;

/**
 * Реестр живых инстанций: по нему клиент выбирает порт нужной рабочей области,
 * не перебирая порты вслепую.
 */
public class InstanceBeaconTest {

    private Path home;
    private String saved;

    @Before
    public void up() throws Exception {
        saved = System.getProperty(McpHome.SYS_PROP);
        home = Files.createTempDirectory("edt-mcp-beacon-");
        System.setProperty(McpHome.SYS_PROP, home.toString());
    }

    @After
    public void down() throws Exception {
        if (saved == null) System.clearProperty(McpHome.SYS_PROP);
        else System.setProperty(McpHome.SYS_PROP, saved);
        deleteTree(home);
    }

    private static InstanceRecord rec(int port, String ws) {
        return new InstanceRecord(ProcessHandle.current().pid(), port, "127.0.0.1",
                "http://127.0.0.1:" + port + "/mcp/sse", "E:\\EDTProjects\\" + ws, ws,
                List.of(ws), "1.22.0", "2026-08-17T22:00:00Z");
    }

    @Test
    public void publish_thenReadAll_roundTrips() throws Exception {
        try (InstanceBeacon b = InstanceBeacon.publish(rec(3002, "Demo"))) {
            List<InstanceRecord> all = InstanceBeacon.readAll();
            assertEquals(1, all.size());
            assertEquals(3002, all.get(0).port());
            assertEquals("Demo", all.get(0).workspaceName());
            assertEquals("E:\\EDTProjects\\Demo", all.get(0).workspacePath());
            assertTrue(all.get(0).projects().contains("Demo"));
            assertEquals("http://127.0.0.1:3002/mcp/sse", all.get(0).sseUrl());
        }
    }

    @Test
    public void close_removesBeacon() throws Exception {
        InstanceBeacon b = InstanceBeacon.publish(rec(3003, "Alpha"));
        b.close();
        assertTrue("после close маячков быть не должно", InstanceBeacon.readAll().isEmpty());
    }

    /**
     * Осиротевший маячок — файл, который никто не держит блокировкой. Такой остаётся
     * после краха процесса: на Windows ОС снимает FileLock сама.
     */
    @Test
    public void readAll_sweepsOrphanedBeacon() throws Exception {
        Files.createDirectories(McpHome.instances());
        Path orphan = McpHome.instances().resolve("999999.json");
        Files.write(orphan, ("\u0000{\"pid\":999999,\"port\":3099}").getBytes(StandardCharsets.UTF_8));

        List<InstanceRecord> all = InstanceBeacon.readAll();

        assertTrue("осиротевший маячок не должен попасть в выдачу", all.isEmpty());
        assertFalse("и должен быть удалён", Files.exists(orphan));
    }

    /** Живой маячок соседствует с осиротевшим и переживает уборку. */
    @Test
    public void readAll_keepsLiveBeaconWhileSweeping() throws Exception {
        Files.createDirectories(McpHome.instances());
        Path orphan = McpHome.instances().resolve("999998.json");
        Files.write(orphan, ("\u0000{\"pid\":999998,\"port\":3098}").getBytes(StandardCharsets.UTF_8));

        try (InstanceBeacon b = InstanceBeacon.publish(rec(3004, "Beta"))) {
            List<InstanceRecord> all = InstanceBeacon.readAll();
            assertEquals("живой остаётся, осиротевший уходит", 1, all.size());
            assertEquals("Beta", all.get(0).workspaceName());
        }
    }

    /** Битый или чужой по формату маячок не должен ронять чтение остальных. */
    @Test
    public void readAll_toleratesForeignFormat() throws Exception {
        Files.createDirectories(McpHome.instances());
        Path f = McpHome.instances().resolve("999997.json");
        Files.write(f, ("\u0000{\"pid\":999997,\"port\":3097,\"workspaceName\":\"X\","
                + "\"somethingNew\":42}").getBytes(StandardCharsets.UTF_8));

        assertTrue(Files.exists(f));
        List<InstanceRecord> all = InstanceBeacon.readAll();
        assertTrue("осиротевший всё равно подметается", all.isEmpty());
    }

    /** Каталога маячков может не быть вовсе — это «инстанций нет», а не ошибка. */
    @Test
    public void readAll_onMissingDirectory_isEmpty() {
        assertTrue(InstanceBeacon.readAll().isEmpty());
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
