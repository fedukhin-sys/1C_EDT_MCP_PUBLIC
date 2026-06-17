package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Unit-тесты skeleton-генератора .mdo внешнего объекта
 * ({@code CreateExternalObjectTool.buildMdo}).
 */
public class CreateExternalObjectBuildMdoTest {

    private static final String CLASS_DP = "c3831ec8-d8d5-4f93-8a22-f9bfae07327f";
    private static final String CLASS_RP = "e41aff26-25cf-4bb6-b6c1-3f478a75f374";

    private static String buildMdo(String kind, String classId, String name,
                                   String synonym, String comment) throws Exception {
        Method m = Class.forName("ru.fedukhin.edt.mcp.tools.md.CreateExternalObjectTool")
                .getDeclaredMethod("buildMdo", String.class, String.class, String.class,
                        String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, kind, classId, name, synonym, comment);
    }

    @Test
    public void dataProcessorSkeletonHasProducedTypesAndContainedObjects() throws Exception {
        String xml = buildMdo("ExternalDataProcessor", CLASS_DP, "ДосудебнаяПретензия",
                "Досудебная претензия", null);
        Document doc = parse(xml);
        Element root = doc.getDocumentElement();
        assertEquals("ExternalDataProcessor", root.getLocalName());

        Element pt = first(root, "producedTypes");
        assertNotNull(pt);
        Element objType = first(pt, "objectType");
        assertNotNull(objType);
        assertFalse(objType.getAttribute("typeId").isEmpty());
        assertFalse(objType.getAttribute("valueTypeId").isEmpty());

        assertEquals("ДосудебнаяПретензия", first(root, "name").getTextContent());
        assertEquals("Досудебная претензия", first(first(root, "synonym"), "value").getTextContent());

        Element co = first(root, "containedObjects");
        assertEquals(CLASS_DP, co.getAttribute("classId"));
        assertFalse(co.getAttribute("objectId").isEmpty());

        // комментарий отсутствует
        assertNull(first(root, "comment"));
        // uuid на корне
        assertFalse(root.getAttribute("uuid").isEmpty());
    }

    @Test
    public void reportUsesReportClassIdAndRootElement() throws Exception {
        String xml = buildMdo("ExternalReport", CLASS_RP, "МойОтчёт", null, "коммент");
        Document doc = parse(xml);
        Element root = doc.getDocumentElement();
        assertEquals("ExternalReport", root.getLocalName());
        assertEquals(CLASS_RP, first(root, "containedObjects").getAttribute("classId"));
        // synonym нет, comment есть
        assertNull(first(root, "synonym"));
        assertEquals("коммент", first(root, "comment").getTextContent());
    }

    @Test
    public void escapesSpecialCharsInSynonym() throws Exception {
        String xml = buildMdo("ExternalDataProcessor", CLASS_DP, "X", "A & B < C", null);
        assertTrue(xml.contains("A &amp; B &lt; C"));
        // и всё ещё валидный XML
        parse(xml);
    }

    // --- helpers ---

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    private static Element first(Element parent, String local) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                String ln = k.getLocalName() != null ? k.getLocalName() : k.getNodeName();
                if (local.equals(ln)) return (Element) k;
            }
        }
        return null;
    }
}
