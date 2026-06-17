package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.core.resources.IFile;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/** Unit-тесты {@link MdoFileEditor#addSpreadsheetTemplate} (регистрация .mxlx-макета). */
public class MdoFileEditorSpreadsheetTemplateTest {

    @Test
    public void addsTemplatesWithSynonymNoTemplateType() throws Exception {
        IFile mdo = mockMdo("""
                <?xml version="1.0" encoding="UTF-8"?>
                <mdclass:ExternalDataProcessor xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
                  <name>ДосудебнаяПретензия</name>
                  <containedObjects classId="c3831ec8-d8d5-4f93-8a22-f9bfae07327f" objectId="x"/>
                </mdclass:ExternalDataProcessor>
                """);
        MdoFileEditor editor = new MdoFileEditor();
        boolean changed = editor.addSpreadsheetTemplate(mdo, "ПФ_MXL_Досудебка", "Досудебная претензия");
        assertTrue(changed);

        Document doc = capturedWrite(mdo);
        Element root = doc.getDocumentElement();
        Element t = first(root, "templates");
        assertNotNull(t);
        assertEquals("ПФ_MXL_Досудебка", first(t, "name").getTextContent());
        assertEquals("Досудебная претензия", first(first(t, "synonym"), "value").getTextContent());
        // spreadsheet → templateType НЕ пишется
        assertNull(first(t, "templateType"));
        assertFalse(t.getAttribute("uuid").isEmpty());
    }

    @Test
    public void idempotentWhenTemplateExists() throws Exception {
        IFile mdo = mockMdo("""
                <?xml version="1.0" encoding="UTF-8"?>
                <mdclass:ExternalDataProcessor xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
                  <name>X</name>
                  <templates uuid="11111111-2222-3333-4444-555555555555">
                    <name>ПФ_MXL_Досудебка</name>
                  </templates>
                </mdclass:ExternalDataProcessor>
                """);
        MdoFileEditor editor = new MdoFileEditor();
        boolean changed = editor.addSpreadsheetTemplate(mdo, "ПФ_MXL_Досудебка", "ignored");
        assertFalse(changed);
        verify(mdo, never()).setContents(any(InputStream.class), anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void omitsSynonymWhenNull() throws Exception {
        IFile mdo = mockMdo("""
                <?xml version="1.0" encoding="UTF-8"?>
                <mdclass:ExternalReport xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
                  <name>X</name>
                </mdclass:ExternalReport>
                """);
        MdoFileEditor editor = new MdoFileEditor();
        assertTrue(editor.addSpreadsheetTemplate(mdo, "Макет", null));
        Document doc = capturedWrite(mdo);
        Element t = first(doc.getDocumentElement(), "templates");
        assertEquals("Макет", first(t, "name").getTextContent());
        assertNull(first(t, "synonym"));
    }

    // --- helpers ---

    private static IFile mockMdo(String xml) throws Exception {
        IFile f = mock(IFile.class);
        when(f.getContents()).thenReturn(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        when(f.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path("/X/X.mdo"));
        return f;
    }

    private static Document capturedWrite(IFile mdo) throws Exception {
        ArgumentCaptor<InputStream> cap = ArgumentCaptor.forClass(InputStream.class);
        verify(mdo).setContents(cap.capture(), anyBoolean(), anyBoolean(), any());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cap.getValue().transferTo(baos);
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(baos.toByteArray()));
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
