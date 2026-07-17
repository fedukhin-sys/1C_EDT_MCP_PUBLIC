package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.AddEnumValueTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * {@code add_enum_value} — до этого перечисления можно было только создать пустыми:
 * {@code create_md_object kind=Enum} давал Enum без значений, а добавить их было нечем.
 *
 * <p>Запись идёт DOM-маршрутом (как {@code add_attribute} после v1.10.2) — без гонок BM.
 */
public class AddEnumValueToolTest {

    private static final String EMPTY_ENUM_MDO =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<mdclass:Enum xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"e0\">\n"
      + "  <producedTypes>\n"
      + "    <refType typeId=\"t1\" valueTypeId=\"t2\"/>\n"
      + "  </producedTypes>\n"
      + "  <name>СтатусыЗаказов</name>\n"
      + "  <commandInterface uuid=\"ci1\"/>\n"
      + "</mdclass:Enum>\n";

    private static final String ENUM_WITH_VALUE_MDO =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<mdclass:Enum xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"e0\">\n"
      + "  <name>СтатусыЗаказов</name>\n"
      + "  <enumValues uuid=\"v1\"><name>Новый</name></enumValues>\n"
      + "</mdclass:Enum>\n";

    private IFile mdoFile;

    private AddEnumValueTool toolFor(String mdoXml) throws Exception {
        mdoFile = mock(IFile.class);
        when(mdoFile.exists()).thenReturn(true);
        when(mdoFile.getContents()).thenAnswer(inv ->
            new ByteArrayInputStream(mdoXml.getBytes(StandardCharsets.UTF_8)));
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getFile("src/Enums/СтатусыЗаказов/СтатусыЗаказов.mdo")).thenReturn(mdoFile);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);
        return new AddEnumValueTool(() -> root, new MdoFileEditor());
    }

    /** Достаёт XML, который инструмент записал в .mdo. */
    private Document writtenDocument() throws Exception {
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(mdoFile).setContents(captor.capture(), anyBoolean(), anyBoolean(),
            any(IProgressMonitor.class));
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(captor.getValue());
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

    private static int positionOf(Node target) {
        NodeList kids = target.getParentNode().getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) == target) return i;
        }
        return -1;
    }

    @Test
    public void addsValueWithNameSynonymAndComment() throws Exception {
        AddEnumValueTool tool = toolFor(EMPTY_ENUM_MDO);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
            "project", "Demo",
            "fqn",     "Enum.СтатусыЗаказов",
            "name",    "Новый",
            "synonym", "Новый заказ",
            "comment", "стартовый статус"));

        assertEquals("Enum.СтатусыЗаказов", result.get("fqn"));
        assertEquals("Новый", result.get("name"));

        Element root = writtenDocument().getDocumentElement();
        Element value = firstChild(root, "enumValues");
        assertNotNull("<enumValues> обязан появиться", value);
        assertTrue("значению нужен собственный uuid", !value.getAttribute("uuid").isEmpty());
        assertEquals("Новый", firstChild(value, "name").getTextContent());

        Element synonym = firstChild(value, "synonym");
        assertNotNull("<synonym> обязан быть записан", synonym);
        assertEquals("ru", firstChild(synonym, "key").getTextContent());
        assertEquals("Новый заказ", firstChild(synonym, "value").getTextContent());
        assertEquals("стартовый статус", firstChild(value, "comment").getTextContent());
    }

    /** EDT требует enumValues до commandInterface — иначе .mdo не по схеме. */
    @Test
    public void insertsValueBeforeCommandInterfaceAnchor() throws Exception {
        AddEnumValueTool tool = toolFor(EMPTY_ENUM_MDO);

        tool.call(Map.of("project", "Demo", "fqn", "Enum.СтатусыЗаказов", "name", "Новый"));

        Element root = writtenDocument().getDocumentElement();
        assertTrue("enumValues обязан стоять перед commandInterface",
            positionOf(firstChild(root, "enumValues")) < positionOf(firstChild(root, "commandInterface")));
    }

    @Test
    public void optionalSynonymAndComment_areOmitted() throws Exception {
        AddEnumValueTool tool = toolFor(EMPTY_ENUM_MDO);

        tool.call(Map.of("project", "Demo", "fqn", "Enum.СтатусыЗаказов", "name", "Закрыт"));

        Element value = firstChild(writtenDocument().getDocumentElement(), "enumValues");
        assertEquals("Закрыт", firstChild(value, "name").getTextContent());
        assertEquals("пустой synonym не должен писаться вовсе", null, firstChild(value, "synonym"));
        assertEquals(null, firstChild(value, "comment"));
    }

    @Test
    public void duplicateName_isRejected() throws Exception {
        AddEnumValueTool tool = toolFor(ENUM_WITH_VALUE_MDO);

        try {
            tool.call(Map.of("project", "Demo", "fqn", "Enum.СтатусыЗаказов", "name", "Новый"));
            fail("повторное значение обязано отбиваться");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Новый"));
        }
    }

    @Test
    public void nonEnumFqn_isRejected() throws Exception {
        AddEnumValueTool tool = toolFor(EMPTY_ENUM_MDO);

        try {
            tool.call(Map.of("project", "Demo", "fqn", "Catalog.Товары", "name", "X"));
            fail("add_enum_value применим только к Enum");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Enum"));
        }
    }
}
