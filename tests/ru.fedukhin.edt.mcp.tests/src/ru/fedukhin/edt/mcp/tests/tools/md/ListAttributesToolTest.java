package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
 * BUG-16: list_attributes reads attributes straight from the .mdo on disk.
 */
public class ListAttributesToolTest {

    private static final String CATALOG_MDO =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"c1\">\n"
      + "  <name>Goods</name>\n"
      + "  <attributes uuid=\"a1\">\n"
      + "    <name>Article</name>\n"
      + "    <type><types>String</types><stringQualifiers><length>50</length></stringQualifiers></type>\n"
      + "    <synonym><key>ru</key><value>Артикул</value></synonym>\n"
      + "  </attributes>\n"
      + "  <attributes uuid=\"a2\">\n"
      + "    <name>Price</name>\n"
      + "    <type><types>Number</types>"
      + "<numberQualifiers><precision>15</precision><scale>2</scale></numberQualifiers></type>\n"
      + "  </attributes>\n"
      + "</mdclass:Catalog>\n";

    private static final String INF_REGISTER_MDO =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<mdclass:InformationRegister xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"r0\">\n"
      + "  <name>Sales</name>\n"
      + "  <dimensions uuid=\"d1\"><name>Период</name><type><types>Date</types></type></dimensions>\n"
      + "  <resources uuid=\"s1\"><name>Сумма</name><type><types>ValueStorage</types></type></resources>\n"
      + "</mdclass:InformationRegister>\n";

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

    @Test
    public void catalog_readsAttributesWithTypesFromMdo() throws Exception {
        IProject project = mockProjectWithMdo("src/Catalogs/Goods/Goods.mdo", CATALOG_MDO);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        ListAttributesTool tool = new ListAttributesTool(() -> root, new MdoFileEditor());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "Catalog.Goods"));

        List<?> attrs = (List<?>) result.get("attributes");
        assertNotNull(attrs);
        assertEquals(2, attrs.size());
        Map<?, ?> a0 = (Map<?, ?>) attrs.get(0);
        assertEquals("Article", a0.get("name"));
        assertEquals("Attribute", a0.get("role"));
        assertEquals("String(50)", a0.get("type"));
        assertEquals("Артикул", a0.get("synonym"));
        Map<?, ?> a1 = (Map<?, ?>) attrs.get(1);
        assertEquals("Price", a1.get("name"));
        assertEquals("Number(15,2)", a1.get("type"));
    }

    @Test
    public void informationRegister_readsDimensionsAndResources() throws Exception {
        IProject project = mockProjectWithMdo("src/InformationRegisters/Sales/Sales.mdo", INF_REGISTER_MDO);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        ListAttributesTool tool = new ListAttributesTool(() -> root, new MdoFileEditor());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "InformationRegister.Sales"));

        List<?> attrs = (List<?>) result.get("attributes");
        assertEquals(2, attrs.size());
        Map<?, ?> dim = (Map<?, ?>) attrs.get(0);
        assertEquals("Период", dim.get("name"));
        assertEquals("Dimension", dim.get("role"));
        assertEquals("Date", dim.get("type"));
        Map<?, ?> res = (Map<?, ?>) attrs.get(1);
        assertEquals("Сумма", res.get("name"));
        assertEquals("Resource", res.get("role"));
        assertEquals("ValueStorage", res.get("type"));
    }

    @Test
    public void commonModule_returnsEmptyArray() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        ListAttributesTool tool = new ListAttributesTool(() -> root, new MdoFileEditor());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "CommonModule.Helper"));

        List<?> attrs = (List<?>) result.get("attributes");
        assertNotNull(attrs);
        assertEquals(0, attrs.size());
    }
}
