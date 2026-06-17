package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.fedukhin.edt.mcp.tools.md.internal.MxlxTemplateBuilder;
import ru.fedukhin.edt.mcp.tools.md.internal.MxlxTemplateBuilder.Cell;
import ru.fedukhin.edt.mcp.tools.md.internal.MxlxTemplateBuilder.Row;

/** Unit-тесты чистого генератора {@link MxlxTemplateBuilder}. */
public class MxlxTemplateBuilderTest {

    private static Cell text(String t, int span, boolean bold, String align) {
        return new Cell(t, null, span, 1, bold, 11, align, "Top", false);
    }
    private static Cell param(String p, int span) {
        return new Cell(null, p, span, 1, false, 11, "Left", "Top", true);
    }

    @Test
    public void twoColumnLetterStructure() throws Exception {
        List<Row> rows = List.of(
                new Row(List.of(param("ИсхНомер", 1), param("Получатель", 1))),
                new Row(List.of(text("ДОСУДЕБНАЯ ПРЕТЕНЗИЯ", 2, true, "center"))),
                new Row(List.of(param("Тело", 2))));
        String xml = new MxlxTemplateBuilder().build("Письмо", List.of(720, 224), rows);
        Document doc = parse(xml);
        Element root = doc.getDocumentElement();

        assertEquals("document", root.getLocalName());

        // 2 колонки
        Element columns = first(root, "columns");
        assertEquals("2", first(columns, "size").getTextContent());
        assertEquals(2, count(columns, "columnsItem"));

        // 3 строки
        assertEquals(3, count(root, "rowsItem"));
        assertEquals("3", first(root, "vgRows").getTextContent());
        assertEquals("3", first(root, "height").getTextContent());

        // namedItem с именем области
        Element named = first(root, "namedItem");
        assertEquals("Письмо", first(named, "name").getTextContent());

        // параметр присутствует
        assertTrue(xml.contains("<parameter>ИсхНомер</parameter>"));
        assertTrue(xml.contains("<parameter>Тело</parameter>"));

        // объединения: строка 1 (заголовок span2) и строка 2 (тело span2) → 2 merge
        NodeList merges = root.getElementsByTagName("merge");
        assertEquals(2, merges.getLength());
        // merge заголовка: r=1, c=0, w=1
        Element m0 = (Element) merges.item(0);
        assertEquals("1", first(m0, "r").getTextContent());
        assertEquals("0", first(m0, "c").getTextContent());
        assertEquals("1", first(m0, "w").getTextContent());

        // форматы: 2 ширины + по 1 на ячейку (2+1+1 = 4 ячейки) = 6
        assertEquals(6, count(root, "format"));

        // fonts дедуплицированы: bold(14? нет — 11 bold) + normal(11) = 2
        // в тесте bold у заголовка size 11 → шрифты (11,false) и (11,true) = 2
        assertEquals(2, count(root, "font"));

        // templateMode
        assertEquals("true", first(root, "templateMode").getTextContent());
    }

    @Test
    public void defaultsForEmptySpec() throws Exception {
        String xml = new MxlxTemplateBuilder().build(null, null, null);
        Document doc = parse(xml);
        Element root = doc.getDocumentElement();
        // одна колонка 800, одна пустая строка, область "Область1"
        assertEquals("1", first(first(root, "columns"), "size").getTextContent());
        assertEquals(1, count(root, "rowsItem"));
        assertEquals("Область1", first(first(root, "namedItem"), "name").getTextContent());
        assertEquals("800", first(first(root, "format"), "width").getTextContent());
    }

    @Test
    public void rowSpanProducesMergeHeight() throws Exception {
        List<Row> rows = List.of(
                new Row(List.of(new Cell("A", null, 1, 2, false, 11, "Left", "Top", true),
                                text("B", 1, false, "left"))),
                new Row(List.of(text("C", 1, false, "left"))));
        String xml = new MxlxTemplateBuilder().build("Обл", List.of(100, 100), rows);
        Document doc = parse(xml);
        Element root = doc.getDocumentElement();
        Element m = first(root, "merge");
        assertNotNull(m);
        assertEquals("0", first(m, "r").getTextContent());
        assertEquals("0", first(m, "c").getTextContent());
        assertEquals("0", first(m, "w").getTextContent()); // span=1 → 0 доп.колонок
        assertEquals("1", first(m, "h").getTextContent()); // rowSpan=2 → 1 доп.строка
    }

    @Test
    public void parameterCellHasFillTypeParameter() throws Exception {
        String xml = new MxlxTemplateBuilder().build("X", List.of(200),
                List.of(new Row(List.of(param("П", 1)))));
        assertTrue("параметр-ячейка → format с fillType Parameter",
                xml.contains("<fillType>Parameter</fillType>"));
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

    private static int count(Element parent, String local) {
        int n = 0;
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                String ln = k.getLocalName() != null ? k.getLocalName() : k.getNodeName();
                if (local.equals(ln)) n++;
            }
        }
        return n;
    }
}
