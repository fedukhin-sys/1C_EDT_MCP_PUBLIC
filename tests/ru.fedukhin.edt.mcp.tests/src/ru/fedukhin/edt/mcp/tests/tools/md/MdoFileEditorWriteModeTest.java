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
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * Unit-тесты на {@link MdoFileEditor#applyInfRegWriteModeToDoc} — post-write
 * dom-fix добавляющий явный {@code <writeMode>Independent</writeMode>}.
 */
public class MdoFileEditorWriteModeTest {

    @Test
    public void addsWriteModeToInfReg() throws Exception {
        Document doc = parse(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<mdclass:InformationRegister xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\""
              + "   uuid=\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\">"
              + "  <name>ТестРегистр</name>"
              + "  <dataLockControlMode>Managed</dataLockControlMode>"
              + "  <dimensions uuid=\"11111111-2222-3333-4444-555555555555\"/>"
              + "</mdclass:InformationRegister>");
        boolean changed = MdoFileEditor.applyInfRegWriteModeToDoc(doc);
        assertTrue("должно изменить", changed);
        Element wm = firstChild(doc.getDocumentElement(), "writeMode");
        assertNotNull("<writeMode> должен появиться", wm);
        assertEquals("Independent", wm.getTextContent());
        // Должен стоять ПЕРЕД <dimensions>
        Element dims = firstChild(doc.getDocumentElement(), "dimensions");
        assertTrue("writeMode перед dimensions", positionOf(wm) < positionOf(dims));
    }

    @Test
    public void idempotent_doesNotDuplicate() throws Exception {
        Document doc = parse(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<mdclass:InformationRegister xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">"
              + "  <name>X</name>"
              + "  <writeMode>RecorderSubordinate</writeMode>"
              + "</mdclass:InformationRegister>");
        boolean changed = MdoFileEditor.applyInfRegWriteModeToDoc(doc);
        assertFalse("уже есть — no-op", changed);
        // Не перезаписали значение
        assertEquals("RecorderSubordinate", firstChild(doc.getDocumentElement(), "writeMode").getTextContent());
    }

    @Test
    public void noOpForNonInfReg() throws Exception {
        Document doc = parse(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<mdclass:AccumulationRegister xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">"
              + "  <name>X</name>"
              + "</mdclass:AccumulationRegister>");
        boolean changed = MdoFileEditor.applyInfRegWriteModeToDoc(doc);
        assertFalse("не InfReg — не трогаем", changed);
        assertNull(firstChild(doc.getDocumentElement(), "writeMode"));
    }

    @Test
    public void appendsAtEndIfNoAnchors() throws Exception {
        // Регистр без dimensions/resources/attributes/extInfo — пишем в конец
        Document doc = parse(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<mdclass:InformationRegister xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">"
              + "  <name>X</name>"
              + "  <dataLockControlMode>Managed</dataLockControlMode>"
              + "</mdclass:InformationRegister>");
        boolean changed = MdoFileEditor.applyInfRegWriteModeToDoc(doc);
        assertTrue(changed);
        Element wm = firstChild(doc.getDocumentElement(), "writeMode");
        assertNotNull(wm);
        assertEquals("Independent", wm.getTextContent());
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) {
                return (Element) k;
            }
        }
        return null;
    }

    private static int positionOf(Element child) {
        Node parent = child.getParentNode();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) == child) return i;
        }
        return -1;
    }
}
