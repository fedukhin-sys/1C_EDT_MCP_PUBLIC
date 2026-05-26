package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.SetMdTypeTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor.AttrLocator;

/**
 * Unit-тесты для {@link SetMdTypeTool} — FQN-парсинг и DOM-операция
 * {@link MdoFileEditor#applySetAttributeTypeToDoc}.
 */
public class SetMdTypeToolTest {

    // -------- FQN parsing --------

    @Test
    public void parseCatalogRootAttribute() throws Exception {
        var p = invokeParse("Catalog.X.Attribute.Y");
        assertEquals("Catalog", p.kind());
        assertEquals("X", p.objectName());
        assertFalse(p.isConstant());
        assertEquals("attributes", p.locator().tag());
        assertNull(p.locator().tsName());
        assertEquals("Y", p.locator().name());
    }

    @Test
    public void parseDocumentTabularSectionColumn() throws Exception {
        var p = invokeParse("Document.X.TabularSection.TS.Y");
        assertEquals("Document", p.kind());
        assertEquals("X", p.objectName());
        assertEquals("attributes", p.locator().tag());
        assertEquals("TS", p.locator().tsName());
        assertEquals("Y", p.locator().name());
    }

    @Test
    public void parseInformationRegisterDimension() throws Exception {
        var p = invokeParse("InformationRegister.X.Dimension.Y");
        assertEquals("dimensions", p.locator().tag());
        assertNull(p.locator().tsName());
        assertEquals("Y", p.locator().name());
    }

    @Test
    public void parseAccumulationRegisterResource() throws Exception {
        var p = invokeParse("AccumulationRegister.X.Resource.КОплате");
        assertEquals("resources", p.locator().tag());
        assertEquals("КОплате", p.locator().name());
    }

    @Test
    public void parseConstantFallback() throws Exception {
        var p = invokeParse("Constant.МойФлаг");
        assertEquals("Constant", p.kind());
        assertEquals("МойФлаг", p.objectName());
        assertTrue(p.isConstant());
        assertNull(p.locator());
    }

    @Test(expected = ToolException.class)
    public void rejectsMalformedFqn() throws Exception {
        invokeParse("Catalog.X");
    }

    @Test(expected = ToolException.class)
    public void rejectsUnknownRole() throws Exception {
        invokeParse("Catalog.X.Foo.Y");
    }

    // -------- DOM apply --------

    @Test
    public void applyOverwritesExistingRootAttributeType() throws Exception {
        Document doc = parse(
                "<mdclass:Catalog xmlns:mdclass='http://g5.1c.ru/v8/dt/metadata/mdclass'>"
              + "  <name>X</name>"
              + "  <attributes uuid='a'>"
              + "    <type><types>String</types><stringQualifiers><length>50</length></stringQualifiers></type>"
              + "    <name>Y</name>"
              + "  </attributes>"
              + "</mdclass:Catalog>");
        boolean replaced = MdoFileEditor.applySetAttributeTypeToDoc(
                doc, new AttrLocator("attributes", null, "Y"), List.of("CatalogRef.Партнеры"));
        assertTrue("должен replace", replaced);
        Element attr = findChild(doc.getDocumentElement(), "attributes");
        Element type = findChild(attr, "type");
        Element types = findChild(type, "types");
        assertEquals("CatalogRef.Партнеры", types.getTextContent());
        // Старый stringQualifiers ушёл вместе со старым <type>
        assertNull(findChild(type, "stringQualifiers"));
    }

    @Test
    public void applyTabularSectionColumn() throws Exception {
        Document doc = parse(
                "<mdclass:Catalog xmlns:mdclass='http://g5.1c.ru/v8/dt/metadata/mdclass'>"
              + "  <name>X</name>"
              + "  <tabularSections uuid='a'>"
              + "    <name>TS</name>"
              + "    <attributes uuid='b'>"
              + "      <type><types>String</types><stringQualifiers><length>10</length></stringQualifiers></type>"
              + "      <name>Y</name>"
              + "    </attributes>"
              + "  </tabularSections>"
              + "</mdclass:Catalog>");
        boolean replaced = MdoFileEditor.applySetAttributeTypeToDoc(
                doc, new AttrLocator("attributes", "TS", "Y"), List.of("Number(15,2)"));
        assertTrue(replaced);
        Element ts = findChild(doc.getDocumentElement(), "tabularSections");
        Element attr = findChild(ts, "attributes");
        Element type = findChild(attr, "type");
        assertEquals("Number", findChild(type, "types").getTextContent());
        Element nq = findChild(type, "numberQualifiers");
        assertEquals("15", findChild(nq, "precision").getTextContent());
        assertEquals("2", findChild(nq, "scale").getTextContent());
    }

    @Test
    public void applyInformationRegisterDimensionComposite() throws Exception {
        Document doc = parse(
                "<mdclass:InformationRegister xmlns:mdclass='http://g5.1c.ru/v8/dt/metadata/mdclass'>"
              + "  <name>X</name>"
              + "  <dimensions uuid='a'>"
              + "    <type><types>AnyRef</types></type>"
              + "    <name>Y</name>"
              + "  </dimensions>"
              + "</mdclass:InformationRegister>");
        boolean replaced = MdoFileEditor.applySetAttributeTypeToDoc(
                doc, new AttrLocator("dimensions", null, "Y"),
                List.of("CatalogRef.Партнеры", "DocumentRef.ЗаказКлиента"));
        assertTrue(replaced);
        Element dim = findChild(doc.getDocumentElement(), "dimensions");
        Element type = findChild(dim, "type");
        int typesCount = countChildren(type, "types");
        assertEquals("Composite type should have 2 <types>", 2, typesCount);
    }

    @Test(expected = ToolException.class)
    public void rejectsMissingAttribute() throws Exception {
        Document doc = parse(
                "<mdclass:Catalog xmlns:mdclass='http://g5.1c.ru/v8/dt/metadata/mdclass'>"
              + "  <name>X</name>"
              + "</mdclass:Catalog>");
        MdoFileEditor.applySetAttributeTypeToDoc(
                doc, new AttrLocator("attributes", null, "NoSuch"), List.of("String(10)"));
    }

    @Test(expected = ToolException.class)
    public void rejectsMissingTabularSection() throws Exception {
        Document doc = parse(
                "<mdclass:Catalog xmlns:mdclass='http://g5.1c.ru/v8/dt/metadata/mdclass'>"
              + "  <name>X</name>"
              + "</mdclass:Catalog>");
        MdoFileEditor.applySetAttributeTypeToDoc(
                doc, new AttrLocator("attributes", "NoTS", "Y"), List.of("String(10)"));
    }

    // -------- helpers --------

    private static SetMdTypeTool.ParsedFqn invokeParse(String fqn) throws Exception {
        java.lang.reflect.Method m = SetMdTypeTool.class.getDeclaredMethod("parseFqn", String.class);
        m.setAccessible(true);
        try {
            return (SetMdTypeTool.ParsedFqn) m.invoke(null, fqn);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception ce) throw ce;
            throw e;
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    private static Element findChild(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) {
                return (Element) k;
            }
        }
        return null;
    }

    private static int countChildren(Element parent, String tag) {
        int n = 0;
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) n++;
        }
        return n;
    }
}
