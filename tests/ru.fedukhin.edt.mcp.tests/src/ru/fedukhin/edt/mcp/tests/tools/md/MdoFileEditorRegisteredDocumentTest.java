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
 * {@link MdoFileEditor#addRegisteredDocumentToDoc}: регистрация документа в журнале —
 * односторонняя запись {@code <registeredDocuments>} с каноничным местом (перед
 * standardAttributes/columns/forms) и идемпотентностью.
 */
public class MdoFileEditorRegisteredDocumentTest {

    @Test
    public void addsRegisteredDocumentBeforeStandardAttributes() throws Exception {
        Document doc = parse(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<mdclass:DocumentJournal xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">"
              + "  <name>ЖурналОпераций</name>"
              + "  <useStandardCommands>true</useStandardCommands>"
              + "  <standardAttributes><name>Type</name></standardAttributes>"
              + "</mdclass:DocumentJournal>");
        boolean changed = MdoFileEditor.addRegisteredDocumentToDoc(doc, "Document.Заказ");
        assertTrue("должно изменить", changed);
        Element reg = firstChild(doc.getDocumentElement(), "registeredDocuments");
        assertNotNull("<registeredDocuments> должен появиться", reg);
        assertEquals("Document.Заказ", reg.getTextContent());
        Element std = firstChild(doc.getDocumentElement(), "standardAttributes");
        assertTrue("registeredDocuments перед standardAttributes",
                positionOf(reg) < positionOf(std));
    }

    @Test
    public void appendsAtEndForMinimalJournal() throws Exception {
        // Минимальный журнал из create_md_object: producedTypes/name/synonym, анчоров нет.
        Document doc = parse(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<mdclass:DocumentJournal xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">"
              + "  <name>Ж</name>"
              + "</mdclass:DocumentJournal>");
        assertTrue(MdoFileEditor.addRegisteredDocumentToDoc(doc, "Document.А"));
        assertNotNull(firstChild(doc.getDocumentElement(), "registeredDocuments"));
    }

    @Test
    public void idempotent_sameDocumentIsNoOp() throws Exception {
        Document doc = parse(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<mdclass:DocumentJournal xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">"
              + "  <name>Ж</name>"
              + "  <registeredDocuments>Document.А</registeredDocuments>"
              + "</mdclass:DocumentJournal>");
        assertFalse("повтор того же документа — no-op",
                MdoFileEditor.addRegisteredDocumentToDoc(doc, "Document.А"));
        assertTrue("другой документ добавляется",
                MdoFileEditor.addRegisteredDocumentToDoc(doc, "Document.Б"));
        NodeList all = doc.getDocumentElement().getElementsByTagName("registeredDocuments");
        assertEquals(2, all.getLength());
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static Element firstChild(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && tag.equals(e.getTagName())) return e;
        }
        return null;
    }

    private static int positionOf(Element el) {
        int i = 0;
        for (Node n = el.getParentNode().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n == el) return i;
            i++;
        }
        return -1;
    }
}
