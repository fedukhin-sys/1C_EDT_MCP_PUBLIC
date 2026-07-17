package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.ListAttributesTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * Табличные части обязаны читаться, а не молча пропадать: MCP умеет их создавать
 * ({@code add_tabular_section}), но disk-route чтения обходил только прямых детей корня с
 * тегами attributes/dimensions/resources, поэтому {@code <tabularSections>} и их колонки
 * были невидимы клиенту.
 *
 * <p>Фикстура повторяет то, что реально пишет {@code MdoFileEditor.addTabularSection}:
 * {@code <tabularSections>} с {@code producedTypes}, {@code name} и inline-колонками
 * {@code <attributes>}.
 */
public class ListAttributesTabularSectionsTest {

    private static final String DOCUMENT_MDO =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<mdclass:Document xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"d0\">\n"
      + "  <name>Заказ</name>\n"
      + "  <attributes uuid=\"a1\">\n"
      + "    <name>Организация</name>\n"
      + "    <type><types>CatalogRef.Организации</types></type>\n"
      + "  </attributes>\n"
      + "  <tabularSections uuid=\"t1\">\n"
      + "    <producedTypes>\n"
      + "      <objectType typeId=\"x1\" valueTypeId=\"x2\"/>\n"
      + "      <rowType typeId=\"x3\" valueTypeId=\"x4\"/>\n"
      + "    </producedTypes>\n"
      + "    <name>Товары</name>\n"
      + "    <synonym><key>ru</key><value>Товары и услуги</value></synonym>\n"
      + "    <attributes uuid=\"c1\">\n"
      + "      <name>Номенклатура</name>\n"
      + "      <type><types>CatalogRef.Номенклатура</types><types>DocumentRef.Заказ</types></type>\n"
      + "      <comment>составной тип</comment>\n"
      + "    </attributes>\n"
      + "    <attributes uuid=\"c2\">\n"
      + "      <name>Количество</name>\n"
      + "      <type><types>Number</types>"
      + "<numberQualifiers><precision>15</precision><scale>3</scale></numberQualifiers></type>\n"
      + "    </attributes>\n"
      + "  </tabularSections>\n"
      + "  <tabularSections uuid=\"t2\">\n"
      + "    <name>Комментарии</name>\n"
      + "  </tabularSections>\n"
      + "</mdclass:Document>\n";

    private static IProject mockProjectWithMdo(String mdoRel, String mdoXml) throws Exception {
        IFile mdoFile = mock(IFile.class);
        when(mdoFile.exists()).thenReturn(true);
        when(mdoFile.getContents()).thenAnswer(inv ->
            new ByteArrayInputStream(mdoXml.getBytes(StandardCharsets.UTF_8)));
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getFile(mdoRel)).thenReturn(mdoFile);
        return project;
    }

    private static Map<String, Object> callListAttributes(String mdoRel, String mdoXml, String fqn)
        throws Exception {
        IProject project = mockProjectWithMdo(mdoRel, mdoXml);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        ListAttributesTool tool = new ListAttributesTool(() -> root, new MdoFileEditor());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
            Map.of("project", "Demo", "fqn", fqn));
        return result;
    }

    @Test
    public void document_readsTabularSectionsWithColumns() throws Exception {
        Map<String, Object> result =
            callListAttributes("src/Documents/Заказ/Заказ.mdo", DOCUMENT_MDO, "Document.Заказ");

        List<?> sections = (List<?>) result.get("tabularSections");
        assertNotNull("tabularSections обязаны присутствовать в результате", sections);
        assertEquals(2, sections.size());

        Map<?, ?> goods = (Map<?, ?>) sections.get(0);
        assertEquals("Товары", goods.get("name"));
        assertEquals("Товары и услуги", goods.get("synonym"));

        List<?> columns = (List<?>) goods.get("attributes");
        assertEquals(2, columns.size());

        Map<?, ?> nomenclature = (Map<?, ?>) columns.get(0);
        assertEquals("Номенклатура", nomenclature.get("name"));
        assertEquals("составной тип", nomenclature.get("comment"));
        assertEquals("составной тип обязан отдаваться списком",
            List.of("CatalogRef.Номенклатура", "DocumentRef.Заказ"), nomenclature.get("type"));

        Map<?, ?> quantity = (Map<?, ?>) columns.get(1);
        assertEquals("Количество", quantity.get("name"));
        assertEquals("Number(15,3)", quantity.get("type"));
    }

    @Test
    public void tabularSectionWithoutColumns_readsAsEmptyList() throws Exception {
        Map<String, Object> result =
            callListAttributes("src/Documents/Заказ/Заказ.mdo", DOCUMENT_MDO, "Document.Заказ");

        List<?> sections = (List<?>) result.get("tabularSections");
        Map<?, ?> comments = (Map<?, ?>) sections.get(1);
        assertEquals("Комментарии", comments.get("name"));
        assertEquals("", comments.get("synonym"));
        assertTrue(((List<?>) comments.get("attributes")).isEmpty());
    }

    /** Плоский attributes — существующий контракт, ТЧ не должны в него подмешиваться. */
    @Test
    public void topLevelAttributes_areUnaffectedByTabularSections() throws Exception {
        Map<String, Object> result =
            callListAttributes("src/Documents/Заказ/Заказ.mdo", DOCUMENT_MDO, "Document.Заказ");

        List<?> attrs = (List<?>) result.get("attributes");
        assertEquals("колонки ТЧ не должны попадать в плоский список реквизитов", 1, attrs.size());
        assertEquals("Организация", ((Map<?, ?>) attrs.get(0)).get("name"));
    }

    /** У kind'ов без .mdo с реквизитами ключ обязан присутствовать пустым, а не отсутствовать. */
    @Test
    public void kindWithoutAttributeBearingMdo_stillReportsEmptyTabularSections() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        ListAttributesTool tool = new ListAttributesTool(() -> root, new MdoFileEditor());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
            Map.of("project", "Demo", "fqn", "CommonModule.Helper"));

        assertTrue(((List<?>) result.get("tabularSections")).isEmpty());
    }
}
