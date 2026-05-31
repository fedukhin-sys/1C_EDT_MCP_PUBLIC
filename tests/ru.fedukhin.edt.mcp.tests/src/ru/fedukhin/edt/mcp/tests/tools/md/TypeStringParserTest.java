package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.ParsedType;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringParser;

public class TypeStringParserTest {

    private final TypeStringParser p = new TypeStringParser();

    @Test public void parsesString() throws Exception {
        ParsedType t = p.parseOne("String");
        assertEquals(ParsedType.Kind.STRING, t.kind());
        assertNull(t.length());                   // unlimited
    }

    @Test public void parsesStringWithLength() throws Exception {
        ParsedType t = p.parseOne("String(25)");
        assertEquals(ParsedType.Kind.STRING, t.kind());
        assertEquals(Integer.valueOf(25), t.length());
    }

    @Test public void parsesNumber() throws Exception {
        ParsedType t = p.parseOne("Number");
        assertEquals(ParsedType.Kind.NUMBER, t.kind());
        assertNull(t.length());
        assertNull(t.precision());
    }

    @Test public void parsesNumberWithLengthAndPrecision() throws Exception {
        ParsedType t = p.parseOne("Number(10,2)");
        assertEquals(ParsedType.Kind.NUMBER, t.kind());
        assertEquals(Integer.valueOf(10), t.length());
        assertEquals(Integer.valueOf(2), t.precision());
    }

    @Test public void parsesNumberWithLengthOnly() throws Exception {
        ParsedType t = p.parseOne("Number(10)");
        assertEquals(Integer.valueOf(10), t.length());
        assertNull(t.precision());
    }

    @Test public void parsesDateAndBoolean() throws Exception {
        assertEquals(ParsedType.Kind.DATE, p.parseOne("Date").kind());
        assertEquals(ParsedType.Kind.BOOLEAN, p.parseOne("Boolean").kind());
    }

    @Test public void parsesCatalogRef() throws Exception {
        ParsedType t = p.parseOne("CatalogRef.Goods");
        assertEquals(ParsedType.Kind.REF, t.kind());
        assertEquals("Catalog", t.refKind());
        assertEquals("Goods", t.refName());
    }

    @Test public void parsesDocumentRef() throws Exception {
        ParsedType t = p.parseOne("DocumentRef.Sale");
        assertEquals(ParsedType.Kind.REF, t.kind());
        assertEquals("Document", t.refKind());
        assertEquals("Sale", t.refName());
    }

    @Test public void parsesEnumRef() throws Exception {
        ParsedType t = p.parseOne("EnumRef.Status");
        assertEquals(ParsedType.Kind.REF, t.kind());
        assertEquals("Enum", t.refKind());
        assertEquals("Status", t.refName());
    }

    @Test public void parsesChartOfCharacteristicTypesRef() throws Exception {
        ParsedType t = p.parseOne("ChartOfCharacteristicTypesRef.Свойства");
        assertEquals(ParsedType.Kind.REF, t.kind());
        assertEquals("ChartOfCharacteristicTypes", t.refKind());
        assertEquals("Свойства", t.refName());
    }

    @Test public void parsesOtherRefKinds() throws Exception {
        assertEquals("ChartOfAccounts", p.parseOne("ChartOfAccountsRef.Основной").refKind());
        assertEquals("ExchangePlan",    p.parseOne("ExchangePlanRef.Обмен").refKind());
        assertEquals("BusinessProcess", p.parseOne("BusinessProcessRef.Задание").refKind());
        assertEquals("Task",            p.parseOne("TaskRef.Поручение").refKind());
    }

    @Test public void parsesValueStorage() throws Exception {
        assertEquals(ParsedType.Kind.VALUE_STORAGE, p.parseOne("ValueStorage").kind());
    }

    @Test public void parsesUuidAndAnyRef() throws Exception {
        assertEquals(ParsedType.Kind.UUID,    p.parseOne("UUID").kind());
        assertEquals(ParsedType.Kind.ANY_REF, p.parseOne("AnyRef").kind());
    }

    @Test public void parsesCompositeArray() throws Exception {
        List<ParsedType> ts = p.parseMany(new String[]{"String(50)", "CatalogRef.Goods"});
        assertEquals(2, ts.size());
        assertEquals(ParsedType.Kind.STRING, ts.get(0).kind());
        assertEquals(ParsedType.Kind.REF, ts.get(1).kind());
    }

    @Test(expected = ToolException.class)
    public void rejectsEmptyString() throws Exception { p.parseOne(""); }

    @Test(expected = ToolException.class)
    public void rejectsUnknownKind() throws Exception { p.parseOne("Frobnicate"); }

    @Test(expected = ToolException.class)
    public void rejectsStringWithNonNumericLength() throws Exception { p.parseOne("String(abc)"); }

    @Test(expected = ToolException.class)
    public void rejectsRefWithoutName() throws Exception { p.parseOne("CatalogRef."); }

    @Test(expected = ToolException.class)
    public void rejectsEmptyArray() throws Exception { p.parseMany(new String[]{}); }
}
