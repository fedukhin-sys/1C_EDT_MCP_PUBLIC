package ru.fedukhin.edt.mcp.tests.tools.form;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.core.resources.IFile;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import ru.fedukhin.edt.mcp.tools.form.internal.FormFileEditor;

/**
 * add_form_button: ссылка на команду и тип кнопки по контексту.
 *
 * <p>11.10.2026: кнопка ОБЩЕЙ команды писалась как {@code Form.Command.CommonCommand.X} с типом
 * {@code UsualButton}, а в командную панель её поставить было нельзя — путь до
 * {@code autoCommandBar} не разрешался. Эталон EDT (формы рабочей конфигурации, 2483 кнопки): в командной панели
 * {@code <type>} не пишется (по умолчанию {@code CommandBarButton}), в обычной группе —
 * {@code <type>UsualButton</type>}; общая команда — {@code <commandName>CommonCommand.X</commandName>};
 * дочерние {@code <items>} идут сразу после {@code <id>} контейнера, до его свойств.
 */
public class FormFileEditorButtonTest {

    private static final String FORM = ""
        + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<form:Form xmlns:form=\"http://g5.1c.ru/v8/dt/form\""
        + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
        + "  <items xsi:type=\"form:FormGroup\">\n"
        + "    <name>ГруппаШапка</name>\n"
        + "    <id>1</id>\n"
        + "    <items xsi:type=\"form:InputField\"><name>Поле</name><id>2</id></items>\n"
        + "    <visible>true</visible>\n"
        + "    <type>UsualGroup</type>\n"
        + "  </items>\n"
        + "  <items xsi:type=\"form:Table\">\n"
        + "    <name>Список</name>\n"
        + "    <id>3</id>\n"
        + "    <autoCommandBar>\n"
        + "      <name>СписокКоманднаяПанель</name>\n"
        + "      <id>4</id>\n"
        + "      <horizontalAlign>Left</horizontalAlign>\n"
        + "    </autoCommandBar>\n"
        + "  </items>\n"
        + "  <autoCommandBar>\n"
        + "    <name>ФормаКоманднаяПанель</name>\n"
        + "    <id>5</id>\n"
        + "    <horizontalAlign>Left</horizontalAlign>\n"
        + "    <autoFill>false</autoFill>\n"
        + "  </autoCommandBar>\n"
        + "</form:Form>\n";

    /** Form.form в памяти: что записал редактор — последний элемент {@code saved}. */
    private static IFile formFile(List<byte[]> saved) throws Exception {
        IFile file = mock(IFile.class);
        when(file.getContents()).thenAnswer(inv -> (InputStream) new ByteArrayInputStream(
            (saved.isEmpty() ? FORM.getBytes(StandardCharsets.UTF_8) : saved.get(saved.size() - 1))));
        doAnswer(inv -> {
            saved.add(((InputStream) inv.getArgument(0)).readAllBytes());
            return null;
        }).when(file).setContents(any(InputStream.class), anyBoolean(), anyBoolean(), any());
        return file;
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static Element itemByName(Document doc, String name) {
        var all = doc.getElementsByTagName("name");
        for (int i = 0; i < all.getLength(); i++) {
            if (name.equals(all.item(i).getTextContent())) return (Element) all.item(i).getParentNode();
        }
        return null;
    }

    private static String child(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && tag.equals(c.getTagName())) return c.getTextContent();
        }
        return null;
    }

    private static List<String> childTags(Element e) {
        List<String> tags = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) tags.add(c.getTagName());
        }
        return tags;
    }

    @Test
    public void commonCommand_inFormCommandBar_canonicalReferenceAndDefaultType() throws Exception {
        List<byte[]> saved = new ArrayList<>();
        FormFileEditor.ButtonResult res = new FormFileEditor().addButton(formFile(saved),
            new FormFileEditor.ButtonSpec("КнопкаПротокол", "CommonCommand.ПротоколОбмена",
                "ФормаКоманднаяПанель", null));

        Document doc = parse(saved.get(0));
        Element button = itemByName(doc, "КнопкаПротокол");
        assertNotNull(button);
        assertEquals("autoCommandBar", ((Element) button.getParentNode()).getTagName());
        assertEquals("CommonCommand.ПротоколОбмена", child(button, "commandName"));
        assertNull("в командной панели тип не пишется (CommandBarButton по умолчанию)", child(button, "type"));
        assertEquals("CommandBarButton", res.type());
        assertEquals("CommonCommand.ПротоколОбмена", res.commandName());
        assertEquals("кнопка — сразу после id панели, до её свойств",
            List.of("name", "id", "items", "horizontalAlign", "autoFill"),
            childTags((Element) button.getParentNode()));
    }

    @Test
    public void tableCommandBar_addressedByFullPath() throws Exception {
        List<byte[]> saved = new ArrayList<>();
        new FormFileEditor().addButton(formFile(saved), new FormFileEditor.ButtonSpec(
            "КнопкаСписка", "CommonCommand.ПротоколОбмена", "Список/СписокКоманднаяПанель", null));

        Element button = itemByName(parse(saved.get(0)), "КнопкаСписка");
        Element bar = (Element) button.getParentNode();
        assertEquals("СписокКоманднаяПанель", child(bar, "name"));
        assertNull(child(button, "type"));
    }

    @Test
    public void formCommand_inUsualGroup_prefixedAndUsualButtonAfterLastItem() throws Exception {
        List<byte[]> saved = new ArrayList<>();
        FormFileEditor.ButtonResult res = new FormFileEditor().addButton(formFile(saved),
            new FormFileEditor.ButtonSpec("КнопкаВыполнить", "Выполнить", "ГруппаШапка", "Выполнить"));

        Element button = itemByName(parse(saved.get(0)), "КнопкаВыполнить");
        assertEquals("Form.Command.Выполнить", child(button, "commandName"));
        assertEquals("UsualButton", child(button, "type"));
        assertEquals("UsualButton", res.type());
        assertEquals("кнопка — после последнего элемента группы, до её свойств",
            List.of("name", "id", "items", "items", "visible", "type"),
            childTags((Element) button.getParentNode()));
    }

    /** Уже полная ссылка на команду формы не получает префикс второй раз. */
    @Test
    public void qualifiedFormCommand_notPrefixedTwice() throws Exception {
        List<byte[]> saved = new ArrayList<>();
        new FormFileEditor().addButton(formFile(saved),
            new FormFileEditor.ButtonSpec("К1", "Form.Command.Выполнить", null, null));
        assertEquals("Form.Command.Выполнить",
            child(itemByName(parse(saved.get(0)), "К1"), "commandName"));
    }

    @Test
    public void formRoot_usualButton() throws Exception {
        List<byte[]> saved = new ArrayList<>();
        new FormFileEditor().addButton(formFile(saved),
            new FormFileEditor.ButtonSpec("К2", "Выполнить", null, null));
        Element button = itemByName(parse(saved.get(0)), "К2");
        assertEquals("form:Form", ((Element) button.getParentNode()).getTagName());
        assertEquals("UsualButton", child(button, "type"));
        assertFalse(new String(saved.get(0), StandardCharsets.UTF_8).contains("Form.Command.Form.Command"));
    }

    @Test
    public void unknownPathSegment_namesIt() throws Exception {
        try {
            new FormFileEditor().addButton(formFile(new ArrayList<>()),
                new FormFileEditor.ButtonSpec("К3", "Выполнить", "НетТакой", null));
            org.junit.Assert.fail("expected ToolException");
        } catch (ru.fedukhin.edt.mcp.core.api.ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("НетТакой"));
        }
    }
}
