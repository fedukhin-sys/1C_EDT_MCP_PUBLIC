package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.TabularSectionAttribute;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.GetMdObjectTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringFormatter;

/**
 * BM-маршрут чтения тоже обязан отдавать табличные части: {@code AttributeReader.readAll}
 * рефлексивно звал только getAttributes/getDimensions/getResources, поэтому
 * {@code get_md_object} на Catalog/Document с ТЧ молча их терял.
 */
public class GetMdObjectTabularSectionsTest {

    private static TabularSectionAttribute column(String name, String synonym) {
        TabularSectionAttribute col = mock(TabularSectionAttribute.class);
        when(col.getName()).thenReturn(name);
        EMap<String, String> syn = new BasicEMap<>();
        if (synonym != null) {
            syn.put("ru", synonym);
        }
        when(col.getSynonym()).thenReturn(syn);
        when(col.getComment()).thenReturn("");
        return col;
    }

    private static CatalogTabularSection section(String name, String synonym,
                                                 List<TabularSectionAttribute> columns) {
        CatalogTabularSection ts = mock(CatalogTabularSection.class);
        when(ts.getName()).thenReturn(name);
        EMap<String, String> syn = new BasicEMap<>();
        if (synonym != null) {
            syn.put("ru", synonym);
        }
        when(ts.getSynonym()).thenReturn(syn);
        when(ts.getComment()).thenReturn("");
        when(ts.getAttributes()).thenReturn(new BasicEList<>(columns));
        return ts;
    }

    private GetMdObjectTool makeToolWithBm(IBmModelManager bm, IBmTransaction txn, IProject project,
                                           IWorkspaceRoot root) {
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(1);
            task.execute(txn);
            return null;
        }).when(bm).executeReadOnlyTask(eq(project), any());
        return new GetMdObjectTool(() -> root, bm,
            mock(IConfigurationProvider.class),
            mock(IV8ProjectManager.class),
            new MdObjectLocator(),
            new MdObjectRegistry(),
            new TypeStringFormatter());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readGoods(Catalog catalog) throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject) catalog);

        IBmModelManager bm = mock(IBmModelManager.class);
        GetMdObjectTool tool = makeToolWithBm(bm, txn, project, root);
        return (Map<String, Object>) tool.call(Map.of("project", "Demo", "fqn", "Catalog.Goods"));
    }

    private static Catalog goodsCatalog(EList<CatalogTabularSection> sections) {
        Catalog catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(catalog.getName()).thenReturn("Goods");
        when(catalog.getSynonym()).thenReturn(new BasicEMap<>());
        when(catalog.getComment()).thenReturn("");
        when(catalog.getAttributes()).thenReturn(new BasicEList<>());
        when(catalog.getTabularSections()).thenReturn(sections);
        return catalog;
    }

    @Test
    public void catalog_readsTabularSectionsWithColumns() throws Exception {
        EList<CatalogTabularSection> sections = new BasicEList<>(List.of(
            section("Товары", "Товары и услуги",
                List.of(column("Номенклатура", "Номенклатура"), column("Количество", null))),
            section("Комментарии", null, List.of())));

        Map<String, Object> result = readGoods(goodsCatalog(sections));

        List<?> read = (List<?>) result.get("tabularSections");
        assertNotNull("tabularSections обязаны присутствовать в результате", read);
        assertEquals(2, read.size());

        Map<?, ?> goods = (Map<?, ?>) read.get(0);
        assertEquals("Товары", goods.get("name"));
        assertEquals("Товары и услуги", goods.get("synonym"));

        List<?> columns = (List<?>) goods.get("attributes");
        assertEquals(2, columns.size());
        assertEquals("Номенклатура", ((Map<?, ?>) columns.get(0)).get("name"));
        assertEquals("Количество", ((Map<?, ?>) columns.get(1)).get("name"));

        Map<?, ?> comments = (Map<?, ?>) read.get(1);
        assertEquals("Комментарии", comments.get("name"));
        assertTrue(((List<?>) comments.get("attributes")).isEmpty());
    }

    @Test
    public void catalogWithoutTabularSections_reportsEmptyList() throws Exception {
        Map<String, Object> result = readGoods(goodsCatalog(new BasicEList<>()));

        assertTrue(((List<?>) result.get("tabularSections")).isEmpty());
    }
}
