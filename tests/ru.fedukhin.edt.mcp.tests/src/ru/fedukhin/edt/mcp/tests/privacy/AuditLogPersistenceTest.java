package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.ipc.McpHome;
import ru.fedukhin.edt.mcp.core.privacy.AuditLog;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

/**
 * get_privacy_audit обязан показывать факты обезличивания всех инстанций EDT,
 * а не свою долю: журнал существует ради 152-ФЗ, и «одна треть событий» делает
 * его бесполезным при параллельных сессиях.
 */
public class AuditLogPersistenceTest {

    private Path home;
    private String saved;

    @Before
    public void up() throws Exception {
        saved = System.getProperty(McpHome.SYS_PROP);
        home = Files.createTempDirectory("edt-mcp-audit-");
        System.setProperty(McpHome.SYS_PROP, home.toString());
    }

    @After
    public void down() throws Exception {
        if (saved == null) System.clearProperty(McpHome.SYS_PROP);
        else System.setProperty(McpHome.SYS_PROP, saved);
        deleteTree(home);
    }

    @Test
    public void persistentLog_survivesNewInstance() {
        new AuditLog(true).record("get_variables", "Demo", Sensitivity.PERSONAL, "Справочник.ФизическиеЛица", 3);

        List<Map<String, Object>> recent = new AuditLog(true).recent(10);

        assertEquals("запись должна читаться новым экземпляром", 1, recent.size());
        assertEquals(3, recent.get(0).get("count"));
        assertEquals("PERSONAL", recent.get(0).get("sensitivity"));
        assertTrue("в журнале не должно быть самих значений ПДн",
                !recent.get(0).containsKey("value"));
    }

    /** Журнал соседней инстанции лежит в своём файле и обязан попасть в выдачу. */
    @Test
    public void recent_mergesFilesOfOtherInstances() throws Exception {
        new AuditLog(true).record("evaluate", "Demo", Sensitivity.PERSONAL, "Тип.Строка", 1);

        Files.createDirectories(McpHome.privacy());
        Files.writeString(McpHome.privacy().resolve("audit-999999.jsonl"),
                "{\"at\":\"2026-08-18T10:00:00Z\",\"tool\":\"query_event_log\",\"infobase\":\"Demo\","
                        + "\"sensitivity\":\"PERSONAL\",\"object\":\"Пользователь\",\"count\":7}\n",
                StandardCharsets.UTF_8);

        List<Map<String, Object>> recent = new AuditLog(true).recent(10);

        assertEquals(2, recent.size());
        assertTrue("должна быть запись чужой инстанции",
                recent.stream().anyMatch(e -> "query_event_log".equals(e.get("tool"))));
        assertTrue("и своя собственная",
                recent.stream().anyMatch(e -> "evaluate".equals(e.get("tool"))));
    }

    /** Битая строка не должна утаскивать за собой остальные записи. */
    @Test
    public void recent_toleratesCorruptedLine() throws Exception {
        Files.createDirectories(McpHome.privacy());
        Files.writeString(McpHome.privacy().resolve("audit-999998.jsonl"),
                "не json\n{\"at\":\"2026-08-18T11:00:00Z\",\"tool\":\"evaluate\",\"count\":1}\n",
                StandardCharsets.UTF_8);

        List<Map<String, Object>> recent = new AuditLog(true).recent(10);

        assertEquals(1, recent.size());
        assertEquals("evaluate", recent.get(0).get("tool"));
    }

    /** Непересистентный журнал (дефолт) диска не касается вовсе. */
    @Test
    public void inMemoryLog_writesNothingToDisk() {
        new AuditLog().record("evaluate", "Demo", Sensitivity.PERSONAL, "Тип.Строка", 1);

        assertTrue("файлов быть не должно", !Files.isDirectory(McpHome.privacy()));
    }

    @Test
    public void recent_respectsLimitAndOrder() {
        AuditLog log = new AuditLog(true);
        for (int i = 1; i <= 5; i++) {
            log.record("tool" + i, "Demo", Sensitivity.PERSONAL, "Объект", i);
        }

        List<Map<String, Object>> recent = log.recent(2);

        assertEquals(2, recent.size());
        assertEquals("отдаются последние по времени", 5, recent.get(1).get("count"));
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
