package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncSnapshot;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectSyncState;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncStateReport;

/** Строки проектов и детали ответа {@code get_infobase_sync_state}. */
public class SyncStateReportTest {

    private static Map<String, byte[]> files(Object... pathThenByte) {
        Map<String, byte[]> out = new HashMap<>();
        for (int i = 0; i < pathThenByte.length; i += 2) {
            out.put((String) pathThenByte[i], SignatureDiffTest.sig((Integer) pathThenByte[i + 1]));
        }
        return out;
    }

    private static EdtSyncSnapshot.Recorded recorded(Map<String, byte[]> files) {
        return new EdtSyncSnapshot.Recorded(files, 1_700_000_000_000L);
    }

    @Test
    public void row_statesAlways_errorOnlyWhenPresent() {
        Map<String, Object> ok = SyncStateReport.row(new ProjectSyncState("Demo", "configuration", "EQUAL", true,
            false, null));
        Map<String, Object> unknown = SyncStateReport.row(new ProjectSyncState("Demo.A", "extension", null, null,
            null, "нет метода"));

        assertEquals("UPDATED", ok.get("updateState"));
        assertFalse(ok.containsKey("error"));
        assertTrue(unknown.containsKey("equalityState"));
        assertNull(unknown.get("updateState"));
        assertEquals("нет метода", unknown.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void details_memoryMatches_diskSame_compact() {
        Map<String, byte[]> current = files("src/a.bsl", 1);
        EdtSyncSnapshot.Read read = new EdtSyncSnapshot.Read(Map.of("Demo", recorded(files("src/a.bsl", 1))), null,
            Map.of("Demo", recorded(files("src/a.bsl", 1))), null);

        Map<String, Object> details = SyncStateReport.details("Demo", current, null, read, 10);

        assertEquals(1, details.get("currentFiles"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) details.get("memory")).get("matches"));
        Map<String, Object> disk = (Map<String, Object>) details.get("disk");
        assertEquals(Boolean.TRUE, disk.get("sameAsMemory"));
        assertFalse(disk.containsKey("matches"));
        assertFalse(details.containsKey("note"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void details_diskDiffers_fullDiffAgainstDisk() {
        EdtSyncSnapshot.Read read = new EdtSyncSnapshot.Read(Map.of("Demo", recorded(files("src/a.bsl", 1))), null,
            Map.of("Demo", recorded(files("src/a.bsl", 9))), null);

        Map<String, Object> disk = (Map<String, Object>) SyncStateReport.details("Demo", files("src/a.bsl", 1), null,
            read, 10).get("disk");

        assertEquals(Boolean.FALSE, disk.get("sameAsMemory"));
        assertEquals(Boolean.FALSE, disk.get("matches"));
        assertEquals(1, disk.get("changedCount"));
        List<Map<String, Object>> changed = (List<Map<String, Object>>) disk.get("changed");
        assertEquals("09", changed.get(0).get("snapshot"));
        assertEquals("01", changed.get(0).get("current"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void details_projectMissingInMemory_allAddedWithNote() {
        EdtSyncSnapshot.Read read = new EdtSyncSnapshot.Read(Map.of("Demo", recorded(files())), null, null, null);

        Map<String, Object> details = SyncStateReport.details("Demo.Склад", files("src/x.bsl", 2, "src/y.bsl", 3),
            null, read, 10);

        assertEquals(2, ((Map<String, Object>) details.get("memory")).get("addedCount"));
        assertTrue(String.valueOf(details.get("note")), String.valueOf(details.get("note"))
            .contains("нет записи этого проекта"));
    }

    @Test
    public void details_noMemorySnapshot_notesOnly() {
        EdtSyncSnapshot.Read read = new EdtSyncSnapshot.Read(null, "в памяти EDT нет состояния", null,
            "не читался");

        Map<String, Object> details = SyncStateReport.details("Demo", files("src/a.bsl", 1), null, read, 10);

        assertFalse(details.containsKey("memory"));
        assertFalse(details.containsKey("disk"));
        String note = String.valueOf(details.get("note"));
        assertTrue(note, note.contains("в памяти EDT нет состояния"));
        assertTrue(note, note.contains("не читался"));
    }

    @Test
    public void details_currentFailure_noteNoDiffs() {
        EdtSyncSnapshot.Read read = new EdtSyncSnapshot.Read(Map.of("Demo", recorded(files("src/a.bsl", 1))), null,
            null, null);

        Map<String, Object> details = SyncStateReport.details("Demo", null, "сервис ресурсов EDT недоступен", read, 10);

        assertNull(details.get("currentFiles"));
        assertFalse(details.containsKey("memory"));
        assertTrue(String.valueOf(details.get("note")).contains("сервис ресурсов EDT недоступен"));
    }

    /** Текущих подписей нет, а диск расходится с памятью — это сведение не теряется. */
    @Test
    @SuppressWarnings("unchecked")
    public void details_noCurrent_diskDiffersFromMemory_reported() {
        EdtSyncSnapshot.Read read = new EdtSyncSnapshot.Read(Map.of("Demo", recorded(files("src/a.bsl", 1))), null,
            Map.of("Demo", recorded(files("src/a.bsl", 9))), null);

        Map<String, Object> disk = (Map<String, Object>) SyncStateReport.details("Demo", null, "нет сервиса", read, 10)
            .get("disk");

        assertEquals(Boolean.FALSE, disk.get("sameAsMemory"));
        assertFalse("без текущих подписей сравнивать не с чем", disk.containsKey("matches"));
    }

    @Test
    public void time_localSeconds() {
        assertTrue(SyncStateReport.time(0L).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"));
    }
}
