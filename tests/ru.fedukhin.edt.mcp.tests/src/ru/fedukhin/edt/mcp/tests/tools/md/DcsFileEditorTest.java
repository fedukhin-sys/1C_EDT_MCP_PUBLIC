package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.fedukhin.edt.mcp.tools.md.CreateDataCompositionSchemaTool;
import ru.fedukhin.edt.mcp.tools.md.internal.DcsFileEditor;

/**
 * Unit-тесты pure-DOM helper'ов {@link DcsFileEditor}. Используем тот же
 * skeleton .dcs ({@link CreateDataCompositionSchemaTool#DCS_SKELETON}), что
 * генерирует {@code create_data_composition_schema} — реалистичный fixture.
 */
public class DcsFileEditorTest {

    private static final String SKELETON = CreateDataCompositionSchemaTool.DCS_SKELETON;

    @Test
    public void addDataSetQuery_addsNewDataSet() throws Exception {
        Document doc = parse(SKELETON);
        boolean added = DcsFileEditor.applyAddDataSetQuery(doc, "НаборЗапрос", null,
                "ВЫБРАТЬ * ИЗ Справочник.Номенклатура");
        assertTrue(added);
        Element ds = findChildByNameTag(doc.getDocumentElement(), "dataSet", "НаборЗапрос");
        assertNotNull(ds);
        assertEquals("DataSetQuery", ds.getAttribute("xsi:type"));
        assertEquals("ИсточникДанных",
                firstChild(ds, "dataSource").getTextContent());
        assertEquals("ВЫБРАТЬ * ИЗ Справочник.Номенклатура",
                firstChild(ds, "query").getTextContent());
    }

    @Test
    public void addDataSetQuery_idempotent() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddDataSetQuery(doc, "Набор2", null, "SELECT 1");
        boolean again = DcsFileEditor.applyAddDataSetQuery(doc, "Набор2", null, "SELECT 1");
        assertFalse("second call must be no-op", again);
    }

    @Test
    public void addDataSetQuery_acceptsEmptyQuery() throws Exception {
        Document doc = parse(SKELETON);
        boolean added = DcsFileEditor.applyAddDataSetQuery(doc, "Пустой", "ИсточникДанных", "");
        assertTrue(added);
        Element ds = findChildByNameTag(doc.getDocumentElement(), "dataSet", "Пустой");
        assertEquals("", firstChild(ds, "query").getTextContent());
    }

    @Test
    public void addDataSetField_addsFieldToExistingDataSet() throws Exception {
        Document doc = parse(SKELETON);
        boolean added = DcsFileEditor.applyAddDataSetField(doc, "НаборДанных1", "Код", "Код");
        assertTrue(added);
        Element ds = findChildByNameTag(doc.getDocumentElement(), "dataSet", "НаборДанных1");
        // Найти <field> с <dataPath>Код</dataPath>
        Element f = null;
        NodeList fields = ds.getChildNodes();
        for (int i = 0; i < fields.getLength(); i++) {
            Node k = fields.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && "field".equals(k.getNodeName())) {
                Element fe = (Element) k;
                Element dp = firstChild(fe, "dataPath");
                if (dp != null && "Код".equals(dp.getTextContent())) {
                    f = fe;
                    break;
                }
            }
        }
        assertNotNull("field 'Код' must be present", f);
        assertEquals("DataSetFieldField", f.getAttribute("xsi:type"));
        assertEquals("Код", firstChild(f, "field").getTextContent());
        // Title should be present
        Element title = firstChild(f, "title");
        assertNotNull(title);
        assertEquals("v8:LocalStringType", title.getAttribute("xsi:type"));
    }

    @Test
    public void addDataSetField_idempotent() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddDataSetField(doc, "НаборДанных1", "Поле1", null);
        boolean again = DcsFileEditor.applyAddDataSetField(doc, "НаборДанных1", "Поле1", "Иное");
        assertFalse("duplicate field must be skipped", again);
    }

    @Test(expected = ru.fedukhin.edt.mcp.core.api.ToolException.class)
    public void addDataSetField_failsIfDataSetNotFound() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddDataSetField(doc, "НетТакого", "X", null);
    }

    @Test
    public void addParameter_addsTypedParameter() throws Exception {
        Document doc = parse(SKELETON);
        boolean added = DcsFileEditor.applyAddParameter(doc, "Период", "xs:dateTime", "Период");
        assertTrue(added);
        Element p = findChildByNameTag(doc.getDocumentElement(), "parameter", "Период");
        assertNotNull(p);
        Element vt = firstChild(p, "valueType");
        assertNotNull("valueType must be present", vt);
        assertEquals("xs:dateTime", firstChild(vt, "v8:Type").getTextContent());
        assertEquals("false", firstChild(p, "useRestriction").getTextContent());
    }

    @Test
    public void addParameter_acceptsUntypedNoTitle() throws Exception {
        Document doc = parse(SKELETON);
        boolean added = DcsFileEditor.applyAddParameter(doc, "Untyped", null, null);
        assertTrue(added);
        Element p = findChildByNameTag(doc.getDocumentElement(), "parameter", "Untyped");
        assertNotNull(p);
        assertNull("no valueType expected", firstChild(p, "valueType"));
        assertNull("no title expected", firstChild(p, "title"));
    }

    @Test
    public void addParameter_idempotent() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddParameter(doc, "X", "xs:string", null);
        boolean again = DcsFileEditor.applyAddParameter(doc, "X", "xs:string", null);
        assertFalse(again);
    }

    @Test
    public void addParameter_insertedBeforeSettingsVariant() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddParameter(doc, "Тест", "xs:boolean", null);
        // Parameter должен идти ПЕРЕД settingsVariant в каноне.
        Element root = doc.getDocumentElement();
        int paramIdx = -1, svIdx = -1;
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE) continue;
            if ("parameter".equals(k.getNodeName())) paramIdx = i;
            if ("settingsVariant".equals(k.getNodeName())) svIdx = i;
        }
        assertTrue("parameter must come before settingsVariant",
                paramIdx >= 0 && svIdx >= 0 && paramIdx < svIdx);
    }

    // ---

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // Match DcsFileEditor: namespaceAware=false для лексического разбора.
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    private static Element findChildByNameTag(Element parent, String tag, String name) {
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

    private static Element firstChild(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) return (Element) k;
        }
        return null;
    }
}
