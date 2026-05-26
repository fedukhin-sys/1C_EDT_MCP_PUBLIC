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
import ru.fedukhin.edt.mcp.tools.md.CreateDataCompositionSchemaTool;
import ru.fedukhin.edt.mcp.tools.md.internal.DcsFileEditor;

/**
 * Unit-тесты v1.12.0 расширения {@link DcsFileEditor}:
 * <ul>
 *   <li>{@code addCalculatedField} — приёмка/идемпотент/anchor;</li>
 *   <li>{@code addTotalField} — c group keys / без / идемпотент;</li>
 *   <li>{@code addDataSetLink} — happy path / parameter / dup / отсутствует один из DataSet'ов;</li>
 *   <li>{@code setDataSetQuery} — replace / idempotent / not-DataSetQuery / not-found.</li>
 * </ul>
 */
public class DcsFileEditorV1120Test {

    private static final String SKELETON = CreateDataCompositionSchemaTool.DCS_SKELETON;

    // ----- calculatedField -----

    @Test
    public void addCalculatedField_happy() throws Exception {
        Document doc = parse(SKELETON);
        boolean added = DcsFileEditor.applyAddCalculatedField(doc, "Итого",
                "Сумма + НДС", "Сумма с НДС");
        assertTrue(added);
        Element cf = findChildByDataPath(doc.getDocumentElement(), "calculatedField", "Итого");
        assertNotNull(cf);
        assertEquals("Сумма + НДС", firstChild(cf, "expression").getTextContent());
        Element title = firstChild(cf, "title");
        assertNotNull("title block expected when title provided", title);
        assertEquals("v8:LocalStringType", title.getAttribute("xsi:type"));
    }

    @Test
    public void addCalculatedField_noTitle() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddCalculatedField(doc, "X", "expr", null);
        Element cf = findChildByDataPath(doc.getDocumentElement(), "calculatedField", "X");
        assertNull("no title block expected when title=null", firstChild(cf, "title"));
    }

    @Test
    public void addCalculatedField_idempotent() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddCalculatedField(doc, "X", "1", null);
        boolean again = DcsFileEditor.applyAddCalculatedField(doc, "X", "2", "other");
        assertFalse(again);
    }

    // ----- totalField -----

    @Test
    public void addTotalField_withGroupKeys() throws Exception {
        Document doc = parse(SKELETON);
        boolean added = DcsFileEditor.applyAddTotalField(doc, "Сумма",
                "Сумма(Сумма)", List.of("Контрагент", "Договор"));
        assertTrue(added);
        Element tf = findChildByDataPath(doc.getDocumentElement(), "totalField", "Сумма");
        assertNotNull(tf);
        NodeList groups = tf.getChildNodes();
        int gCount = 0;
        for (int i = 0; i < groups.getLength(); i++) {
            Node n = groups.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "group".equals(n.getNodeName())) gCount++;
        }
        assertEquals(2, gCount);
    }

    @Test
    public void addTotalField_emptyGroupKeys() throws Exception {
        Document doc = parse(SKELETON);
        boolean added = DcsFileEditor.applyAddTotalField(doc, "X", "Минимум(X)", List.of());
        assertTrue(added);
        Element tf = findChildByDataPath(doc.getDocumentElement(), "totalField", "X");
        assertNull("no <group> children expected", firstChild(tf, "group"));
    }

    @Test
    public void addTotalField_idempotent() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddTotalField(doc, "X", "expr", null);
        boolean again = DcsFileEditor.applyAddTotalField(doc, "X", "other", List.of("K"));
        assertFalse(again);
    }

    // ----- dataSetLink -----

    @Test
    public void addDataSetLink_happy_withParameter() throws Exception {
        // Build .dcs with two DataSets
        Document doc = parse(SKELETON);
        // Skeleton имеет один DataSetObject 'НаборДанных1'; добавим второй.
        DcsFileEditor.applyAddDataSetQuery(doc, "НаборДанных2", null, "SELECT 1");

        boolean added = DcsFileEditor.applyAddDataSetLink(doc, "НаборДанных1", "НаборДанных2",
                "Поле1", "Поле2", "Param1");
        assertTrue(added);
        Element link = firstChild(doc.getDocumentElement(), "dataSetLink");
        assertNotNull(link);
        assertEquals("НаборДанных1",  firstChild(link, "sourceDataSet").getTextContent());
        assertEquals("НаборДанных2",  firstChild(link, "destinationDataSet").getTextContent());
        assertEquals("Поле1",         firstChild(link, "sourceExpression").getTextContent());
        assertEquals("Поле2",         firstChild(link, "destinationExpression").getTextContent());
        assertEquals("Param1",        firstChild(link, "parameter").getTextContent());
        assertEquals("false",         firstChild(link, "parameterListAllowed").getTextContent());
    }

    @Test
    public void addDataSetLink_noParameter_doesNotAddParameterListAllowed() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddDataSetQuery(doc, "Ds2", null, "");
        DcsFileEditor.applyAddDataSetLink(doc, "НаборДанных1", "Ds2", "A", "B", null);
        Element link = firstChild(doc.getDocumentElement(), "dataSetLink");
        assertNull("parameter must be absent",            firstChild(link, "parameter"));
        assertNull("parameterListAllowed must be absent", firstChild(link, "parameterListAllowed"));
    }

    @Test
    public void addDataSetLink_idempotent() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddDataSetQuery(doc, "Ds2", null, "");
        DcsFileEditor.applyAddDataSetLink(doc, "НаборДанных1", "Ds2", "A", "B", null);
        boolean again = DcsFileEditor.applyAddDataSetLink(doc, "НаборДанных1", "Ds2", "A", "B", "ignore");
        assertFalse(again);
    }

    @Test(expected = ToolException.class)
    public void addDataSetLink_failsIfSourceMissing() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddDataSetLink(doc, "Missing", "НаборДанных1", "A", "B", null);
    }

    @Test(expected = ToolException.class)
    public void addDataSetLink_failsIfDestMissing() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddDataSetLink(doc, "НаборДанных1", "Missing", "A", "B", null);
    }

    // ----- setDataSetQuery -----

    @Test
    public void setDataSetQuery_replacesText() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddDataSetQuery(doc, "Q1", null, "OLD QUERY");
        boolean updated = DcsFileEditor.applySetDataSetQuery(doc, "Q1", "NEW QUERY");
        assertTrue(updated);
        Element ds = findChildByName(doc.getDocumentElement(), "dataSet", "Q1");
        assertEquals("NEW QUERY", firstChild(ds, "query").getTextContent());
    }

    @Test
    public void setDataSetQuery_idempotentWhenSame() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddDataSetQuery(doc, "Q1", null, "SAME");
        boolean updated = DcsFileEditor.applySetDataSetQuery(doc, "Q1", "SAME");
        assertFalse(updated);
    }

    @Test(expected = ToolException.class)
    public void setDataSetQuery_failsOnDataSetObject() throws Exception {
        Document doc = parse(SKELETON);
        // Skeleton's НаборДанных1 — DataSetObject, не Query.
        DcsFileEditor.applySetDataSetQuery(doc, "НаборДанных1", "X");
    }

    @Test(expected = ToolException.class)
    public void setDataSetQuery_failsOnMissingDataSet() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applySetDataSetQuery(doc, "Missing", "X");
    }

    // ----- helpers -----

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    private static Element findChildByName(Element parent, String tag, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE || !tag.equals(k.getNodeName())) continue;
            Element e = (Element) k;
            Element n = firstChild(e, "name");
            if (n != null && name.equals(n.getTextContent())) return e;
        }
        return null;
    }

    private static Element findChildByDataPath(Element parent, String tag, String dataPath) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE || !tag.equals(k.getNodeName())) continue;
            Element e = (Element) k;
            Element dp = firstChild(e, "dataPath");
            if (dp != null && dataPath.equals(dp.getTextContent())) return e;
        }
        return null;
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) return (Element) k;
        }
        return null;
    }
}
