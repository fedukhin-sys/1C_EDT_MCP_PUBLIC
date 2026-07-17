package ru.fedukhin.edt.mcp.tests.tools.eventlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogQuery;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogReader;

public class EventLogReaderTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String LGF = ""
        + "1CV8LOG(ver 2.0)\nuuid\n\n"
        + "{1,uuid-1,\"\",1},\n"
        + "{1,uuid-2,\"ФедухинАА\",4},\n"
        + "{2,\"ASUS-TUF\",1},\n"
        + "{3,\"1CV8C\",6},\n"
        + "{3,\"BackgroundJob\",5},\n"
        + "{4,\"_$Session$_.Start\",1},\n"
        + "{4,\"_$Data$_.Update\",13},\n"
        + "{4,\"_$Session$_.ConfigExtensionApplyError\",9},\n"
        + "{6,\"ASUS-TUF\",1},\n"
        + "{7,1560,1}\n";

    private static String evt(long date, String severity, int userSeq, int eventSeq, String comment, long session) {
        return "{" + date + ",N,{0,0}," + userSeq + ",1,6,123," + eventSeq + "," + severity + ",\""
            + comment.replace("\"", "\"\"") + "\",0,{\"B\",0},\"\",1,1,0," + session + ",0,{0}}";
    }

    private void writeLog(Path dir, String partitionName, List<String> events) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1Cv8.lgf"), LGF, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("1CV8LOG(ver 2.0)\nuuid\n\n");
        for (int i = 0; i < events.size(); i++) {
            sb.append(events.get(i));
            sb.append(i < events.size() - 1 ? ",\n" : "\n");
        }
        Files.writeString(dir.resolve(partitionName), sb.toString(), StandardCharsets.UTF_8);
    }

    @Test
    public void filtersByUserAndSeverity() throws Exception {
        Path dir = tmp.newFolder("log").toPath();
        writeLog(dir, "20260406000000.lgp", List.of(
            evt(20260406120000L, "I", 1, 1, "noise",   1),
            evt(20260407120000L, "E", 4, 9, "Расширение ЕССКонтракты применено с ошибкой", 7),
            evt(20260408120000L, "E", 1, 9, "Расширение ЕССКонтракты применено с ошибкой", 8),
            evt(20260409120000L, "I", 4, 13, "Update", 7)
        ));
        EventLogQuery q = new EventLogQuery().from("2026-04-01").to("2026-05-31T23:59:59");
        q.severity(List.of("Error"));
        q.commentContains = "ессКонтракты";
        EventLogReader.Page page = new EventLogReader().read(dir, q);
        assertEquals(2, page.matchedTotal);
        assertEquals(2, page.records.size());
        assertEquals("2026-04-07T12:00:00", page.records.get(0).dateIso);
    }

    @Test
    public void userFilter_findsAllSessionEventsForUser() throws Exception {
        Path dir = tmp.newFolder("log").toPath();
        writeLog(dir, "20260406000000.lgp", List.of(
            evt(20260406120000L, "I", 1, 1, "other user start", 1),
            evt(20260407100000L, "I", 4, 1, "Фед session start", 7),
            evt(20260407110000L, "I", 4, 13, "Фед updates",      7),
            evt(20260407120000L, "I", 4, 1, "Фед session finish",7)
        ));
        EventLogQuery q = new EventLogQuery();
        q.user(List.of("ФедухинАА"));
        EventLogReader.Page page = new EventLogReader().read(dir, q);
        assertEquals(3, page.matchedTotal);
        assertEquals(3, page.records.size());
        // ordering = ascending by default
        assertEquals("2026-04-07T10:00:00", page.records.get(0).dateIso);
    }

    @Test
    public void offsetLimit_paginates() throws Exception {
        Path dir = tmp.newFolder("log").toPath();
        writeLog(dir, "20260406000000.lgp", List.of(
            evt(20260406120000L, "I", 4, 1, "a", 1),
            evt(20260406120001L, "I", 4, 1, "b", 1),
            evt(20260406120002L, "I", 4, 1, "c", 1),
            evt(20260406120003L, "I", 4, 1, "d", 1),
            evt(20260406120004L, "I", 4, 1, "e", 1)
        ));
        EventLogQuery q = new EventLogQuery();
        q.user(List.of("ФедухинАА"));
        q.offset = 1;
        q.limit = 2;
        EventLogReader.Page page = new EventLogReader().read(dir, q);
        assertEquals(5, page.matchedTotal);
        assertEquals(2, page.records.size());
        assertEquals("b", page.records.get(0).comment);
        assertEquals("c", page.records.get(1).comment);
    }

    @Test
    public void datePartitioning_skipsFarOutOfRangeFiles() throws Exception {
        Path dir = tmp.newFolder("log").toPath();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1Cv8.lgf"), LGF, StandardCharsets.UTF_8);
        // Three partitions: 2026-03-21, 2026-04-13, 2026-05-25.
        // Query window = 2026-05-01 .. 2026-05-31 should include 04-13 (carry-over) + 05-25.
        for (String name : List.of("20260321000000.lgp", "20260413000000.lgp", "20260525000000.lgp")) {
            Files.writeString(dir.resolve(name),
                "1CV8LOG(ver 2.0)\nuuid\n\n" + evt(20260525100000L, "I", 1, 1, "x", 1) + "\n",
                StandardCharsets.UTF_8);
        }
        List<Path> ps = EventLogReader.listPartitionsInRange(dir, 20260501000000L, 20260531235959L, false);
        // expect 04-13 (carry) + 05-25
        assertEquals(2, ps.size());
        assertEquals("20260413000000.lgp", ps.get(0).getFileName().toString());
        assertEquals("20260525000000.lgp", ps.get(1).getFileName().toString());
    }

    /**
     * Ключевой баг date_desc: окно [offset, offset+limit) применялось в порядке сканирования
     * (внутри партиции — по возрастанию даты), и только потом страница переворачивалась. При
     * limit меньше числа совпадений клиент получал самые СТАРЫЕ записи, разложенные задом наперёд,
     * то есть ровно противоположное запрошенному.
     */
    @Test
    public void descendingWithLimit_returnsNewestRecords() throws Exception {
        Path dir = tmp.newFolder("log").toPath();
        List<String> events = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            events.add(evt(20260406120000L + i, "I", 4, 1, "e" + i, 1));
        }
        writeLog(dir, "20260406000000.lgp", events);

        EventLogQuery q = new EventLogQuery();
        q.user(List.of("ФедухинАА"));
        q.descending = true;
        q.limit = 3;
        EventLogReader.Page page = new EventLogReader().read(dir, q);

        assertEquals(10, page.matchedTotal);
        assertEquals(3, page.records.size());
        assertEquals("e10", page.records.get(0).comment);
        assertEquals("e9",  page.records.get(1).comment);
        assertEquals("e8",  page.records.get(2).comment);
    }

    /** offset в descending обязан отсчитываться от самой новой записи. */
    @Test
    public void descendingWithOffset_skipsNewestRecords() throws Exception {
        Path dir = tmp.newFolder("log").toPath();
        List<String> events = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            events.add(evt(20260406120000L + i, "I", 4, 1, "e" + i, 1));
        }
        writeLog(dir, "20260406000000.lgp", events);

        EventLogQuery q = new EventLogQuery();
        q.user(List.of("ФедухинАА"));
        q.descending = true;
        q.offset = 2;
        q.limit = 2;
        EventLogReader.Page page = new EventLogReader().read(dir, q);

        assertEquals(2, page.records.size());
        assertEquals("e8", page.records.get(0).comment);
        assertEquals("e7", page.records.get(1).comment);
    }

    /**
     * Страница через несколько партиций: партиции перечисляются newest-first, а записи внутри
     * них — oldest-first, поэтому без сортировки порядок выходил несогласованным.
     */
    @Test
    public void descendingAcrossPartitions_isGloballyOrdered() throws Exception {
        Path dir = tmp.newFolder("log").toPath();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1Cv8.lgf"), LGF, StandardCharsets.UTF_8);
        writeLog(dir, "20260405000000.lgp", List.of(
            evt(20260405120000L, "I", 4, 1, "old1", 1),
            evt(20260405120001L, "I", 4, 1, "old2", 1)));
        writeLog(dir, "20260406000000.lgp", List.of(
            evt(20260406120000L, "I", 4, 1, "new1", 1),
            evt(20260406120001L, "I", 4, 1, "new2", 1)));

        EventLogQuery q = new EventLogQuery();
        q.user(List.of("ФедухинАА"));
        q.descending = true;
        q.limit = 3;
        EventLogReader.Page page = new EventLogReader().read(dir, q);

        assertEquals(4, page.matchedTotal);
        assertEquals(List.of("new2", "new1", "old2"),
            page.records.stream().map(r -> r.comment).toList());
    }

    @Test
    public void descendingOrder_reversesResults() throws Exception {
        Path dir = tmp.newFolder("log").toPath();
        writeLog(dir, "20260406000000.lgp", List.of(
            evt(20260406120000L, "I", 4, 1, "first", 1),
            evt(20260406120001L, "I", 4, 1, "last",  1)
        ));
        EventLogQuery q = new EventLogQuery();
        q.user(List.of("ФедухинАА"));
        q.descending = true;
        EventLogReader.Page page = new EventLogReader().read(dir, q);
        assertEquals("last", page.records.get(0).comment);
        assertEquals("first", page.records.get(1).comment);
    }
}
