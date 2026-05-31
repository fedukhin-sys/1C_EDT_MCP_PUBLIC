package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.internal.ParsedType;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringFormatter;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringParser;

public class TypeStringFormatterTest {

    private final TypeStringFormatter f = new TypeStringFormatter();
    private final TypeStringParser    p = new TypeStringParser();

    @Test public void formatString()           { assertEquals("String",       f.formatOne(ParsedType.string(null))); }
    @Test public void formatStringLen()        { assertEquals("String(25)",   f.formatOne(ParsedType.string(25))); }
    @Test public void formatNumber()           { assertEquals("Number",       f.formatOne(ParsedType.number(null, null))); }
    @Test public void formatNumberLen()        { assertEquals("Number(10)",   f.formatOne(ParsedType.number(10, null))); }
    @Test public void formatNumberLenPrec()    { assertEquals("Number(10,2)", f.formatOne(ParsedType.number(10, 2))); }
    @Test public void formatDate()             { assertEquals("Date",         f.formatOne(ParsedType.date())); }
    @Test public void formatBoolean()          { assertEquals("Boolean",      f.formatOne(ParsedType.bool())); }
    @Test public void formatCatalogRef()       { assertEquals("CatalogRef.Goods",   f.formatOne(ParsedType.ref("Catalog", "Goods"))); }
    @Test public void formatDocumentRef()      { assertEquals("DocumentRef.Sale",   f.formatOne(ParsedType.ref("Document", "Sale"))); }
    @Test public void formatEnumRef()          { assertEquals("EnumRef.Status",     f.formatOne(ParsedType.ref("Enum", "Status"))); }
    @Test public void formatValueStorage()     { assertEquals("ValueStorage", f.formatOne(ParsedType.valueStorage())); }
    @Test public void formatUuid()             { assertEquals("UUID",         f.formatOne(ParsedType.uuid())); }
    @Test public void formatAnyRef()           { assertEquals("AnyRef",       f.formatOne(ParsedType.anyRef())); }
    @Test public void formatChartOfCharacteristicTypesRef() {
        assertEquals("ChartOfCharacteristicTypesRef.Свойства",
                f.formatOne(ParsedType.ref("ChartOfCharacteristicTypes", "Свойства")));
    }

    @Test
    public void roundtripCovers_all_basic_forms() throws Exception {
        String[] cases = {
            "String", "String(50)", "Number", "Number(10)", "Number(10,2)",
            "Date", "Boolean", "ValueStorage", "UUID", "AnyRef",
            "CatalogRef.Goods", "DocumentRef.Sale", "EnumRef.Status",
            "ChartOfCharacteristicTypesRef.Свойства", "ChartOfAccountsRef.Основной"
        };
        for (String s : cases) {
            assertEquals("roundtrip: " + s, s, f.formatOne(p.parseOne(s)));
        }
    }

    @Test
    public void formatManyReturnsSingleString_whenOneType() {
        Object r = f.formatMany(List.of(ParsedType.string(25)));
        assertEquals("String(25)", r);
    }

    @Test
    public void formatManyReturnsArray_whenComposite() {
        Object r = f.formatMany(Arrays.asList(ParsedType.string(50), ParsedType.ref("Catalog", "Goods")));
        assertTrue(r instanceof List<?>);
        @SuppressWarnings("unchecked") List<String> l = (List<String>) r;
        assertEquals(Arrays.asList("String(50)", "CatalogRef.Goods"), l);
    }
}
