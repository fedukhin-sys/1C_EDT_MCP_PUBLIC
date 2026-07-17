package ru.fedukhin.edt.mcp.tests.tools.eventlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogReferences;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventRecord;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.LgpParser;

public class LgpParserTest {

    private static EventLogReferences refs() {
        EventLogReferences r = new EventLogReferences();
        r.users.put(1, new EventLogReferences.User("uuid-1", ""));
        r.users.put(4, new EventLogReferences.User("22222222-2222-2222-2222-222222222222", "ИвановИИ"));
        r.computers.put(1, "ASUS-TUF");
        r.applications.put(5, "BackgroundJob");
        r.applications.put(6, "1CV8C");
        r.events.put(6, "_$Session$_.DataZoneChange");
        r.events.put(9, "_$Session$_.ConfigExtensionApplyError");
        r.metadata.put(1, new EventLogReferences.Metadata("m-uuid", "ПараметрСеанса.X"));
        r.servers.put(1, "ASUS-TUF");
        r.mainPorts.put(1, 1560);
        return r;
    }

    private static final String SAMPLE =
        "1CV8LOG(ver 2.0)\nuuid\n\n"
        + "{20260504000006,N,{0,0},1,1,5,1233,6,I,\"\",1,{\"B\",0},\"\",1,1,0,4,0,{2,1,1,2,1}},\n"
        + "{20260504000008,C,{2454a189a4080,5bd},4,1,6,1233,9,E,\"Расширение ДемоРасширение применено с ошибкой\",1,{\"U\",\"u\"},\"\",1,1,0,4,0,{2,1,1,2,1}}\n";

    /**
     * Активную партицию кластер дописывает прямо во время чтения, поэтому её хвост штатно
     * обрывается посреди записи. Раньше это валило весь запрос: IOException из токенизатора
     * поднимался наружу как ToolException, даже если 99% партиции уже прочитано.
     */
    @Test
    public void truncatedTail_yieldsWholeRecordsAndPartialFlag() throws Exception {
        String truncated = SAMPLE.substring(0, SAMPLE.indexOf("Расширение"));
        List<EventRecord> out = new ArrayList<>();
        LgpParser p = new LgpParser(refs());

        LgpParser.StreamOutcome outcome = p.stream(
            new BufferedReader(new StringReader(truncated)), r -> true, ev -> { out.add(ev); return true; });

        assertTrue("рваный хвост обязан помечаться partial", outcome.partial());
        assertEquals("целая запись до обрыва обязана дойти до клиента", 1, out.size());
        assertEquals(20260504000006L, out.get(0).dateRaw);
    }

    /** Если не прочитано вообще ничего — это не «дописываемый хвост», а битый файл. */
    @Test
    public void garbageFromTheStart_stillThrows() {
        LgpParser p = new LgpParser(refs());
        try {
            p.stream(new BufferedReader(new StringReader("1CV8LOG(ver 2.0)\nuuid\n\n{20260504")),
                r -> true, ev -> true);
            fail("битый файл без единой целой записи обязан падать");
        } catch (IOException expected) {
            // ожидаемо
        }
    }

    @Test
    public void decodesAllFields() throws Exception {
        List<EventRecord> out = new ArrayList<>();
        LgpParser p = new LgpParser(refs());
        long scanned = p.stream(new BufferedReader(new StringReader(SAMPLE)), r -> true,
            ev -> { out.add(ev); return true; }).total();
        assertEquals(2, scanned);
        assertEquals(2, out.size());

        EventRecord e0 = out.get(0);
        assertEquals(20260504000006L, e0.dateRaw);
        assertEquals("2026-05-04T00:00:06", e0.dateIso);
        assertEquals("N", e0.txStatus);
        assertNull("txId of {0,0} should be null", e0.txId);
        assertEquals("", e0.user);
        assertEquals("ASUS-TUF", e0.computer);
        assertEquals("BackgroundJob", e0.application);
        assertEquals(1233L, e0.connectionId);
        assertEquals("_$Session$_.DataZoneChange", e0.event);
        assertEquals("I", e0.severityCode);
        assertEquals("Information", e0.severity);
        assertEquals("ПараметрСеанса.X", e0.metadata);
        assertEquals(Integer.valueOf(1560), e0.mainPort);
        assertEquals(4L, e0.session);

        EventRecord e1 = out.get(1);
        assertEquals("C", e1.txStatus);
        assertEquals("2454a189a4080/5bd", e1.txId);
        assertEquals("ИвановИИ", e1.user);
        assertEquals("22222222-2222-2222-2222-222222222222", e1.userUuid);
        assertEquals("1CV8C", e1.application);
        assertEquals("_$Session$_.ConfigExtensionApplyError", e1.event);
        assertEquals("Error", e1.severity);
        assertEquals("Расширение ДемоРасширение применено с ошибкой", e1.comment);
    }

    @Test
    public void earlyStop_viaSinkReturningFalse() throws Exception {
        List<EventRecord> out = new ArrayList<>();
        LgpParser p = new LgpParser(refs());
        p.stream(new BufferedReader(new StringReader(SAMPLE)), r -> true, ev -> { out.add(ev); return false; });
        assertEquals(1, out.size());
    }

    @Test
    public void severityDecoding() {
        assertEquals("Information", LgpParser.decodeSeverity("I"));
        assertEquals("Warning",     LgpParser.decodeSeverity("W"));
        assertEquals("Error",       LgpParser.decodeSeverity("E"));
        assertEquals("Note",        LgpParser.decodeSeverity("N"));
        assertNull(LgpParser.decodeSeverity(null));
    }

    @Test
    public void isoRoundTrip() {
        long raw = 20260406123456L;
        assertEquals("2026-04-06T12:34:56", LgpParser.toIso(raw));
        assertEquals(raw, LgpParser.fromIso("2026-04-06T12:34:56"));
        assertEquals(20260406000000L, LgpParser.fromIso("2026-04-06"));
    }
}
