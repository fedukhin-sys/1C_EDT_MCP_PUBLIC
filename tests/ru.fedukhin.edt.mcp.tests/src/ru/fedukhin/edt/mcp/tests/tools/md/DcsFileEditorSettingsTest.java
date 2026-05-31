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
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.CreateDataCompositionSchemaTool;
import ru.fedukhin.edt.mcp.tools.md.internal.DcsFileEditor;

/**
 * Юнит-тесты Stage 8g (2026-05-31) settingsVariant-editing операций:
 * {@code addSettingsGrouping} / {@code addSettingsFilter} / {@code setSettingsParameterValue}.
 *
 * <p>Все три работают внутри {@code <settingsVariant>/<dcsset:settings>} skeleton'а из
 * {@link CreateDataCompositionSchemaTool#DCS_SKELETON}.
 */
public class DcsFileEditorSettingsTest {

    private static final String SKELETON = CreateDataCompositionSchemaTool.DCS_SKELETON;

    // ----- addSettingsGrouping -----

    @Test
    public void addSettingsGrouping_happy_addsGroupItemField() throws Exception {
        Document doc = parse(SKELETON);
        boolean added = DcsFileEditor.applyAddSettingsGrouping(doc, "Основной", "Номенклатура", "Items");
        assertTrue(added);

        Element groupItems = findGroupItems(doc);
        assertNotNull("groupItems must be created", groupItems);
        Element item = firstChildWithXsiType(groupItems, "dcsset:item", "dcsset:GroupItemField");
        assertNotNull(item);
        assertEquals("Номенклатура", firstChild(item, "dcsset:field").getTextContent());
        assertEquals("Items", firstChild(item, "dcsset:groupType").getTextContent());
        assertEquals("None", firstChild(item, "dcsset:periodAdditionType").getTextContent());
    }

    @Test
    public void addSettingsGrouping_defaultGroupType_isItems() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddSettingsGrouping(doc, "Основной", "X", null);
        Element item = firstChildWithXsiType(findGroupItems(doc), "dcsset:item", "dcsset:GroupItemField");
        assertEquals("Items", firstChild(item, "dcsset:groupType").getTextContent());
    }

    @Test
    public void addSettingsGrouping_idempotent() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddSettingsGrouping(doc, "Основной", "X", "Items");
        boolean again = DcsFileEditor.applyAddSettingsGrouping(doc, "Основной", "X", "Hierarchy");
        assertFalse("duplicate (variant, field) must be no-op", again);
        // и group type не меняется
        Element item = firstChildWithXsiType(findGroupItems(doc), "dcsset:item", "dcsset:GroupItemField");
        assertEquals("Items", firstChild(item, "dcsset:groupType").getTextContent());
    }

    @Test(expected = ToolException.class)
    public void addSettingsGrouping_failsOnMissingVariant() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddSettingsGrouping(doc, "НетТакого", "X", "Items");
    }

    // ----- addSettingsFilter -----

    @Test
    public void addSettingsFilter_happy_addsFilterItemComparison() throws Exception {
        Document doc = parse(SKELETON);
        boolean added = DcsFileEditor.applyAddSettingsFilter(doc, "Основной",
                "Номенклатура.ВидНоменклатуры", "Equal", false);
        assertTrue(added);

        Element filter = findChildByTag(findSettings(doc), "dcsset:filter");
        assertNotNull(filter);
        Element item = firstChildWithXsiType(filter, "dcsset:item", "dcsset:FilterItemComparison");
        assertNotNull(item);
        assertEquals("false", firstChild(item, "dcsset:use").getTextContent());
        Element left = firstChild(item, "dcsset:left");
        assertEquals("dcscor:Field", left.getAttribute("xsi:type"));
        assertEquals("Номенклатура.ВидНоменклатуры", left.getTextContent());
        assertEquals("Equal", firstChild(item, "dcsset:comparisonType").getTextContent());
    }

    @Test
    public void addSettingsFilter_useTrue_writesTrue() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddSettingsFilter(doc, "Основной", "X", "Equal", true);
        Element item = firstChildWithXsiType(findChildByTag(findSettings(doc), "dcsset:filter"),
                "dcsset:item", "dcsset:FilterItemComparison");
        assertEquals("true", firstChild(item, "dcsset:use").getTextContent());
    }

    @Test
    public void addSettingsFilter_idempotent() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddSettingsFilter(doc, "Основной", "X", "Equal", false);
        boolean again = DcsFileEditor.applyAddSettingsFilter(doc, "Основной", "X", "Equal", true);
        assertFalse(again);
    }

    @Test
    public void addSettingsFilter_diffComparisonType_addsSecond() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applyAddSettingsFilter(doc, "Основной", "X", "Equal", false);
        boolean another = DcsFileEditor.applyAddSettingsFilter(doc, "Основной", "X", "NotEqual", false);
        assertTrue("different comparisonType must be a new filter", another);
        Element filter = findChildByTag(findSettings(doc), "dcsset:filter");
        assertEquals(2, countChildren(filter, "dcsset:item"));
    }

    // ----- setSettingsParameterValue -----

    @Test
    public void setSettingsParameterValue_addsNewValue() throws Exception {
        Document doc = parse(SKELETON);
        boolean changed = DcsFileEditor.applySetSettingsParameterValue(doc, "Основной",
                "Период", "2025-01-01T00:00:00");
        assertTrue(changed);

        Element settings = findSettings(doc);
        Element dp = findChildByTag(settings, "dcsset:dataParameters");
        assertNotNull(dp);
        Element item = firstChildWithXsiType(dp, "dcscor:item", "dcsset:SettingsParameterValue");
        assertEquals("Период", firstChild(item, "dcscor:parameter").getTextContent());
        Element val = firstChild(item, "dcscor:value");
        assertEquals("2025-01-01T00:00:00", val.getTextContent());
        assertFalse(val.hasAttribute("xsi:nil"));
    }

    @Test
    public void setSettingsParameterValue_nullValue_writesXsiNil() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applySetSettingsParameterValue(doc, "Основной", "Param", null);
        Element item = firstChildWithXsiType(
                findChildByTag(findSettings(doc), "dcsset:dataParameters"),
                "dcscor:item", "dcsset:SettingsParameterValue");
        Element val = firstChild(item, "dcscor:value");
        assertEquals("true", val.getAttribute("xsi:nil"));
        assertEquals("", val.getTextContent());
    }

    @Test
    public void setSettingsParameterValue_replaceExisting() throws Exception {
        Document doc = parse(SKELETON);
        DcsFileEditor.applySetSettingsParameterValue(doc, "Основной", "P", "old");
        DcsFileEditor.applySetSettingsParameterValue(doc, "Основной", "P", "new");

        Element dp = findChildByTag(findSettings(doc), "dcsset:dataParameters");
        assertEquals("only one entry for parameter P", 1, countChildren(dp, "dcscor:item"));
        Element item = firstChildWithXsiType(dp, "dcscor:item", "dcsset:SettingsParameterValue");
        assertEquals("new", firstChild(item, "dcscor:value").getTextContent());
    }

    // ----- helpers -----

    private static Element findGroupItems(Document doc) {
        Element settings = findSettings(doc);
        NodeList kids = settings.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"dcsset:item".equals(n.getNodeName())) continue;
            Element it = (Element) n;
            if ("dcsset:StructureItemGroup".equals(it.getAttribute("xsi:type"))) {
                return findChildByTag(it, "dcsset:groupItems");
            }
        }
        return null;
    }

    private static Element findSettings(Document doc) {
        Element root = doc.getDocumentElement();
        Element variant = findChildByTag(root, "settingsVariant");
        return findChildByTag(variant, "dcsset:settings");
    }

    private static Element findChildByTag(Element parent, String tag) {
        if (parent == null) return null;
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) return (Element) k;
        }
        return null;
    }

    private static Element firstChild(Element parent, String tag) {
        return findChildByTag(parent, tag);
    }

    private static Element firstChildWithXsiType(Element parent, String tag, String xsiType) {
        if (parent == null) return null;
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE || !tag.equals(k.getNodeName())) continue;
            Element e = (Element) k;
            if (xsiType.equals(e.getAttribute("xsi:type"))) return e;
        }
        return null;
    }

    private static int countChildren(Element parent, String tag) {
        if (parent == null) return 0;
        NodeList kids = parent.getChildNodes();
        int c = 0;
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) c++;
        }
        return c;
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }
}
