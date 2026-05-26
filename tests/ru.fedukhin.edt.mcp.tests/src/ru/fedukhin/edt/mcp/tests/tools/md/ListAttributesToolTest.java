package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.common.util.EList;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import ru.fedukhin.edt.mcp.tools.md.ListAttributesTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringFormatter;

public class ListAttributesToolTest {

    @Test
    public void catalog_withOneAttribute_returnsSingleEntryArray() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        CatalogAttribute attr = mock(CatalogAttribute.class);
        when(attr.getName()).thenReturn("Article");
        when(attr.getSynonym()).thenReturn(new BasicEMap<>());
        when(attr.getComment()).thenReturn("");

        EList<CatalogAttribute> attrs = new BasicEList<>(List.of(attr));
        Catalog catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(catalog.getAttributes()).thenReturn(attrs);

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject) catalog);

        IBmModelManager bm = mock(IBmModelManager.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(1);
            task.execute(txn);
            return null;
        }).when(bm).executeReadOnlyTask(eq(project), any());

        ListAttributesTool tool = new ListAttributesTool(() -> root, bm, new MdObjectLocator(), new TypeStringFormatter());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "Catalog.Goods"));

        List<?> attributeList = (List<?>) result.get("attributes");
        assertNotNull(attributeList);
        assertEquals(1, attributeList.size());
        Map<?, ?> entry = (Map<?, ?>) attributeList.get(0);
        assertEquals("Article", entry.get("name"));
        assertEquals("Attribute", entry.get("role"));
    }

    @Test
    public void commonModule_returnsEmptyArray() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        CommonModule cm = mock(CommonModule.class, withSettings().extraInterfaces(IBmObject.class));

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("CommonModule.Helper")).thenReturn((IBmObject) cm);

        IBmModelManager bm = mock(IBmModelManager.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(1);
            task.execute(txn);
            return null;
        }).when(bm).executeReadOnlyTask(eq(project), any());

        ListAttributesTool tool = new ListAttributesTool(() -> root, bm, new MdObjectLocator(), new TypeStringFormatter());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "CommonModule.Helper"));

        List<?> attributeList = (List<?>) result.get("attributes");
        assertNotNull(attributeList);
        assertEquals(0, attributeList.size());
    }
}
