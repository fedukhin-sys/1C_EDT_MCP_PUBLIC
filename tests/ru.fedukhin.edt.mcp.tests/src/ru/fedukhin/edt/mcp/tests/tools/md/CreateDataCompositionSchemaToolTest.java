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

/**
 * Unit-тесты на skeleton .dcs и логику регистрации в Report .mdo.
 *
 * <p>Полный {@code call()} тест требует workspace + IFile mock; здесь проверяем:
 * <ul>
 *   <li>skeleton .dcs парсится как валидный XML;</li>
 *   <li>содержит обязательные namespace declarations + dataSource + dataSet + settingsVariant;</li>
 *   <li>cyrillic корректно в UTF-8 (без mojibake).</li>
 * </ul>
 */
public class CreateDataCompositionSchemaToolTest {

    @Test
    public void skeletonIsValidXml() throws Exception {
        Document doc = parse(CreateDataCompositionSchemaTool.DCS_SKELETON);
        Element root = doc.getDocumentElement();
        assertEquals("DataCompositionSchema", root.getLocalName());
        assertEquals(
                "http://v8.1c.ru/8.1/data-composition-system/schema",
                root.getNamespaceURI());
    }

    @Test
    public void skeletonHasRequiredNamespaces() {
        // Все namespace'ы которые EDT-генерированный .dcs использует — должны быть declared.
        String s = CreateDataCompositionSchemaTool.DCS_SKELETON;
        assertTrue("dcsset ns required", s.contains("xmlns:dcsset=\"http://v8.1c.ru/8.1/data-composition-system/settings\""));
        assertTrue("dcscor ns required", s.contains("xmlns:dcscor=\"http://v8.1c.ru/8.1/data-composition-system/core\""));
        assertTrue("v8 ns required",    s.contains("xmlns:v8=\"http://v8.1c.ru/8.1/data/core\""));
        assertTrue("xsi ns required",   s.contains("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""));
    }

    @Test
    public void skeletonHasDataSourceAndDataSet() throws Exception {
        Document doc = parse(CreateDataCompositionSchemaTool.DCS_SKELETON);
        Element root = doc.getDocumentElement();
        Element ds  = firstByLocal(root, "dataSource");
        assertNotNull("dataSource required", ds);
        Element name = firstByLocal(ds, "name");
        assertEquals("ИсточникДанных", name.getTextContent());

        Element dset = firstByLocal(root, "dataSet");
        assertNotNull("dataSet required", dset);
        assertEquals("DataSetObject",
                dset.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type"));
        assertEquals("НаборДанных1", firstByLocal(dset, "name").getTextContent());
    }

    @Test
    public void skeletonHasSettingsVariantОсновной() throws Exception {
        Document doc = parse(CreateDataCompositionSchemaTool.DCS_SKELETON);
        Element root = doc.getDocumentElement();
        Element sv = firstByLocal(root, "settingsVariant");
        assertNotNull("settingsVariant required", sv);
        Element name = firstByLocal(sv, "name");
        assertNotNull("dcsset:name required", name);
        assertEquals("Основной", name.getTextContent());
    }

    @Test
    public void skeletonCyrillicIsValidUtf8() {
        // Гарантия: «ИсточникДанных», «НаборДанных1», «Основной» в строке как UTF-8 native
        String s = CreateDataCompositionSchemaTool.DCS_SKELETON;
        assertTrue(s.contains("ИсточникДанных"));
        assertTrue(s.contains("НаборДанных1"));
        assertTrue(s.contains("Основной"));
        // Mojibake-проверка: не должно быть double-encoded артефактов
        assertFalse("mojibake artefact 'Р°' (double UTF-8) detected", s.contains("Р°"));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    private static Element firstByLocal(Element parent, String localName) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                String ln = k.getLocalName();
                if (ln == null) ln = k.getNodeName();
                if (localName.equals(ln)) return (Element) k;
            }
        }
        return null;
    }
}
