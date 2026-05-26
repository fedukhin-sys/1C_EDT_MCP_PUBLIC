package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.eclipse.core.resources.IFile;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * Unit-тесты для {@link MdoFileEditor#addReportDcsTemplate} —
 * регистрация DCS-template в Report .mdo.
 */
public class MdoFileEditorReportDcsTest {

    @Test
    public void addsBothMainAndTemplatesToEmptyReport() throws Exception {
        IFile mdoFile = mockReportMdoIO("""
                <?xml version="1.0" encoding="UTF-8"?>
                <mdclass:Report xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
                  <name>МойОтчет</name>
                </mdclass:Report>
                """);
        MdoFileEditor editor = new MdoFileEditor();
        boolean changed = editor.addReportDcsTemplate(mdoFile, "МойОтчет", "ОсновнаяСхема");
        assertTrue(changed);

        Document doc = capturedWrite(mdoFile);
        Element root = doc.getDocumentElement();
        Element main = firstByLocal(root, "mainDataCompositionSchema");
        assertNotNull("mainDataCompositionSchema added", main);
        assertEquals("Report.МойОтчет.Template.ОсновнаяСхема", main.getTextContent());

        Element templates = firstByLocal(root, "templates");
        assertNotNull("<templates> added", templates);
        assertEquals("ОсновнаяСхема", firstByLocal(templates, "name").getTextContent());
        assertEquals("DataCompositionSchema",
                firstByLocal(templates, "templateType").getTextContent());
        assertNotNull("templates has uuid", templates.getAttribute("uuid"));
    }

    @Test
    public void idempotentWhenBothAlreadyPresent() throws Exception {
        IFile mdoFile = mockReportMdoIO("""
                <?xml version="1.0" encoding="UTF-8"?>
                <mdclass:Report xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
                  <name>X</name>
                  <mainDataCompositionSchema>Report.X.Template.ОсновнаяСхема</mainDataCompositionSchema>
                  <templates uuid="11111111-2222-3333-4444-555555555555">
                    <name>ОсновнаяСхема</name>
                    <templateType>DataCompositionSchema</templateType>
                  </templates>
                </mdclass:Report>
                """);
        MdoFileEditor editor = new MdoFileEditor();
        boolean changed = editor.addReportDcsTemplate(mdoFile, "X", "ОсновнаяСхема");
        assertFalse("idempotent — nothing to add", changed);
        // verify save was NOT called
        verify(mdoFile, never()).setContents(any(InputStream.class), anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void addsOnlyMissingTemplateWhenMainAlreadyExistsForDifferentName() throws Exception {
        IFile mdoFile = mockReportMdoIO("""
                <?xml version="1.0" encoding="UTF-8"?>
                <mdclass:Report xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
                  <name>X</name>
                  <mainDataCompositionSchema>Report.X.Template.Старая</mainDataCompositionSchema>
                  <templates uuid="11111111-2222-3333-4444-555555555555">
                    <name>Старая</name>
                    <templateType>DataCompositionSchema</templateType>
                  </templates>
                </mdclass:Report>
                """);
        MdoFileEditor editor = new MdoFileEditor();
        boolean changed = editor.addReportDcsTemplate(mdoFile, "X", "Новая");
        assertTrue("added <templates> entry для Новая", changed);

        Document doc = capturedWrite(mdoFile);
        Element root = doc.getDocumentElement();
        // mainDataCompositionSchema НЕ перезаписан (уже был — не трогаем)
        assertEquals("Report.X.Template.Старая",
                firstByLocal(root, "mainDataCompositionSchema").getTextContent());
        // Но Новая templates entry появилась
        NodeList templates = root.getElementsByTagName("templates");
        assertEquals("обе templates entries present", 2, templates.getLength());
    }

    // -------- helpers --------

    private static IFile mockReportMdoIO(String xml) throws Exception {
        IFile f = mock(IFile.class);
        when(f.getContents()).thenReturn(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        when(f.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path("/X/X.mdo"));
        return f;
    }

    private static Document capturedWrite(IFile mdoFile) throws Exception {
        ArgumentCaptor<InputStream> cap = ArgumentCaptor.forClass(InputStream.class);
        verify(mdoFile).setContents(cap.capture(), anyBoolean(), anyBoolean(), any());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cap.getValue().transferTo(baos);
        return parse(baos.toByteArray());
    }

    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(bytes));
    }

    @SuppressWarnings("unused")
    private static byte[] toBytes(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        t.transform(new DOMSource(doc), new StreamResult(baos));
        return baos.toByteArray();
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
