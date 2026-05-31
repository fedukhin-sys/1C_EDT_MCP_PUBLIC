package ru.fedukhin.edt.mcp.tests.tools.eventlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.LgToken;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.LgTokenizer;

public class LgTokenizerTest {

    @Test
    public void emptyReader_returnsNull() throws Exception {
        LgTokenizer t = new LgTokenizer(new StringReader(""));
        assertNull(t.nextRecord());
    }

    @Test
    public void singleAtomRecord_parses() throws Exception {
        LgTokenizer t = new LgTokenizer(new StringReader("{1}"));
        LgToken rec = t.nextRecord();
        assertNotNull(rec);
        assertTrue(rec.isList());
        assertEquals(1, rec.items.size());
        assertEquals(Long.valueOf(1), rec.items.get(0).asLong());
        assertNull(t.nextRecord());
    }

    @Test
    public void mixedRecord_parsesAtomStringNested() throws Exception {
        // {2,"ASUS-TUF",1}
        LgTokenizer t = new LgTokenizer(new StringReader("{2,\"ASUS-TUF\",1}"));
        LgToken rec = t.nextRecord();
        assertEquals(3, rec.items.size());
        assertEquals(Long.valueOf(2), rec.items.get(0).asLong());
        assertTrue(rec.items.get(1).isString());
        assertEquals("ASUS-TUF", rec.items.get(1).asString());
        assertEquals(Long.valueOf(1), rec.items.get(2).asLong());
    }

    @Test
    public void escapedQuoteInString_handled() throws Exception {
        // 1C uses "" inside strings as an escaped double-quote.
        LgTokenizer t = new LgTokenizer(new StringReader("{\"He said \"\"hi\"\"\"}"));
        LgToken rec = t.nextRecord();
        assertEquals("He said \"hi\"", rec.items.get(0).asString());
    }

    @Test
    public void nestedList_parses() throws Exception {
        // {0,{1,2,{3}}}
        LgTokenizer t = new LgTokenizer(new StringReader("{0,{1,2,{3}}}"));
        LgToken rec = t.nextRecord();
        assertEquals(2, rec.items.size());
        LgToken inner = rec.items.get(1);
        assertTrue(inner.isList());
        assertEquals(3, inner.items.size());
        assertTrue(inner.items.get(2).isList());
        assertEquals(Long.valueOf(3), inner.items.get(2).items.get(0).asLong());
    }

    @Test
    public void twoRecords_separatedByCommaAndNewline() throws Exception {
        LgTokenizer t = new LgTokenizer(new StringReader("{1},\n{2}\n"));
        assertEquals(Long.valueOf(1), t.nextRecord().items.get(0).asLong());
        assertEquals(Long.valueOf(2), t.nextRecord().items.get(0).asLong());
        assertNull(t.nextRecord());
    }

    @Test
    public void hexAtomKeptAsString() throws Exception {
        // 1C uses hex for transaction ids: {2454a189a4080,5bd}
        LgTokenizer t = new LgTokenizer(new StringReader("{2454a189a4080,5bd}"));
        LgToken rec = t.nextRecord();
        assertEquals("2454a189a4080", rec.items.get(0).text);
        assertEquals("5bd", rec.items.get(1).text);
        // Not a decimal — asLong() returns null
        assertNull(rec.items.get(0).asLong());
    }

    @Test
    public void bareIdentifier_parsedAsAtom() throws Exception {
        // Severity codes I/W/E/N and txStatus N/C/U/R are bare identifiers
        LgTokenizer t = new LgTokenizer(new StringReader("{N,I,\"x\"}"));
        LgToken rec = t.nextRecord();
        assertEquals("N", rec.items.get(0).text);
        assertEquals("I", rec.items.get(1).text);
        assertTrue(rec.items.get(0).isAtom());
        assertTrue(rec.items.get(1).isAtom());
    }
}
