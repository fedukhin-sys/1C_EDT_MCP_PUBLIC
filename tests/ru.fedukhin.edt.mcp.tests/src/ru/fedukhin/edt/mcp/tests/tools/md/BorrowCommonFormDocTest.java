package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectBorrower;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectBorrower.KindMeta;

/**
 * Тесты конструктора adopted .mdo для CommonForm. Проверяет что в результирующем
 * документе появляется обязательный {@code <form>Extended</form>} внутри
 * {@code <extension xsi:type="mdclassExtension:CommonFormExtension">}.
 *
 * <p>Полноценный unit-test для {@code MdObjectBorrower.borrow()} не пишется — все
 * existing borrow-сценарии в репо проверяются через live smoke. Этот тест ловит
 * именно регрессию по {@code <form>Extended</form>}, которая отличает рабочую
 * adopted CommonForm от broken-skeleton варианта.
 */
public class BorrowCommonFormDocTest {

    @Test
    public void buildAdoptedDoc_commonForm_hasFormExtendedElement() throws Exception {
        Document base = parseBaseCommonForm();
        Element baseRoot = base.getDocumentElement();

        Document adopted = invokeBuildAdoptedDoc(
                baseRoot, "CommonForm", "TestForm",
                "11111111-2222-3333-4444-555555555555",
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                new KindMeta("CommonForms", "commonForms", "CommonFormExtension"),
                List.of());

        Element root = adopted.getDocumentElement();
        Element ext = firstElement(root, "extension");
        assertNotNull("extension element required", ext);
        assertEquals(
                "mdclassExtension:CommonFormExtension",
                ext.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type"));

        Element extConfigObj = firstElement(ext, "extendedConfigurationObject");
        assertNotNull("extendedConfigurationObject required", extConfigObj);
        assertEquals("Checked", extConfigObj.getTextContent());

        Element form = firstElement(ext, "form");
        assertNotNull("CommonForm extension MUST contain <form>Extended</form> — без этого "
                + "EDT не считает форму extended и редактор формы зависает", form);
        assertEquals("Extended", form.getTextContent());
    }

    @Test
    public void buildAdoptedDoc_catalog_doesNotAddFormElement() throws Exception {
        // <form>Extended</form> — это специфика CommonForm. Для Catalog/Document/etc
        // этот тег не должен появляться (мы бы случайно сломали adopted-схему).
        Document base = parseBaseCommonForm();
        Element baseRoot = base.getDocumentElement();

        Document adopted = invokeBuildAdoptedDoc(
                baseRoot, "Catalog", "Goods",
                "11111111-2222-3333-4444-555555555555",
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                new KindMeta("Catalogs", "catalogs", "CatalogExtension"),
                List.of());

        Element ext = firstElement(adopted.getDocumentElement(), "extension");
        assertNotNull(ext);
        assertNull("Catalog extension MUST NOT contain <form>", firstElement(ext, "form"));
    }

    @Test
    public void buildAdoptedDoc_catalog_copiesOwnersFromBase() throws Exception {
        // A1 fix: при borrow Catalog с <owners> в base — в adopted .mdo должны появиться
        // те же <owners> ссылки. Без них deploy валится «нельзя добавлять без загрузки
        // родительского». Cascade-borrow в borrow() заимствует owner-catalog отдельно;
        // здесь проверяем только что <owners> элементы перекочёвывают в adopted XML.
        Document base = parseBaseCommonForm();
        Element baseRoot = base.getDocumentElement();

        Document adopted = invokeBuildAdoptedDoc(
                baseRoot, "Catalog", "Goods",
                "11111111-2222-3333-4444-555555555555",
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                new KindMeta("Catalogs", "catalogs", "CatalogExtension"),
                List.of("Catalog.Номенклатура", "Catalog.НаборыУпаковок"));

        Element root = adopted.getDocumentElement();
        NodeList ownersList = root.getElementsByTagName("owners");
        assertEquals("expected exactly 2 <owners> elements", 2, ownersList.getLength());
        assertEquals("Catalog.Номенклатура", ownersList.item(0).getTextContent());
        assertEquals("Catalog.НаборыУпаковок", ownersList.item(1).getTextContent());
    }

    private static Document parseBaseCommonForm() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<mdclass:CommonForm xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\""
                + " uuid=\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\">"
                + "  <name>TestForm</name>"
                + "</mdclass:CommonForm>";
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    /** Reflection-вызов private static MdObjectBorrower.buildAdoptedDoc. */
    private static Document invokeBuildAdoptedDoc(Element baseRoot, String kind, String name,
                                                  String adoptedUuid, String baseUuid, KindMeta meta,
                                                  List<String> ownersFqns)
            throws Exception {
        Method m = MdObjectBorrower.class.getDeclaredMethod(
                "buildAdoptedDoc", Element.class, String.class, String.class,
                String.class, String.class, KindMeta.class, List.class);
        m.setAccessible(true);
        return (Document) m.invoke(null, baseRoot, kind, name, adoptedUuid, baseUuid, meta, ownersFqns);
    }

    private static Element firstElement(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) {
                return (Element) k;
            }
        }
        return null;
    }
}
