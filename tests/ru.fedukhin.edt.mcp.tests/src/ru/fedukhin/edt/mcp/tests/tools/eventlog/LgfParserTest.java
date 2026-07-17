package ru.fedukhin.edt.mcp.tests.tools.eventlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogReferences;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.LgfParser;

public class LgfParserTest {

    private static final String SAMPLE = ""
        + "1CV8LOG(ver 2.0)\n"
        + "b848b3a4-80bc-4e6f-b8c6-347cecd07ce6\n"
        + "\n"
        + "{1,071523a4-516f-4fce-ba4b-0d11ab7a1893,\"\",1},\n"
        + "{2,\"ASUS-TUF\",1},\n"
        + "{3,\"Designer\",1},\n"
        + "{6,\"ASUS-TUF\",1},\n"
        + "{7,1560,1},\n"
        + "{4,\"_$Session$_.Start\",1},\n"
        + "{3,\"BackgroundJob\",2},\n"
        + "{4,\"_$Transaction$_.Begin\",2},\n"
        + "{5,041cecfd-cef2-45d9-90e8-afe0d97d2d95,\"ПараметрСеанса.ОбластьДанныхИспользование\",1},\n"
        + "{1,22222222-2222-2222-2222-222222222222,\"ИвановИИ\",4},\n"
        + "{3,\"1CV8C\",6}\n";

    @Test
    public void parses_allReferenceTypes() throws Exception {
        EventLogReferences refs = new LgfParser().parse(new BufferedReader(new StringReader(SAMPLE)));

        assertEquals(2, refs.users.size());
        assertEquals("ИвановИИ", refs.users.get(4).name);
        assertEquals("22222222-2222-2222-2222-222222222222", refs.users.get(4).uuid);
        assertEquals("", refs.users.get(1).name);

        assertEquals("ASUS-TUF", refs.computers.get(1));

        assertEquals("Designer", refs.applications.get(1));
        assertEquals("BackgroundJob", refs.applications.get(2));
        assertEquals("1CV8C", refs.applications.get(6));

        assertEquals("_$Session$_.Start", refs.events.get(1));
        assertEquals("_$Transaction$_.Begin", refs.events.get(2));

        assertEquals("ПараметрСеанса.ОбластьДанныхИспользование", refs.metadata.get(1).fullName);

        assertEquals("ASUS-TUF", refs.servers.get(1));
        assertEquals(Integer.valueOf(1560), refs.mainPorts.get(1));
    }

    @Test
    public void emptyHeader_doesNotCrash() throws Exception {
        EventLogReferences refs = new LgfParser().parse(new BufferedReader(new StringReader(
            "1CV8LOG(ver 2.0)\nuuid\n\n")));
        assertNotNull(refs);
        assertEquals(0, refs.users.size());
    }
}
