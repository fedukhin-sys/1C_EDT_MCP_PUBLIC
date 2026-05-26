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
import org.eclipse.emf.common.util.EList;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.RenameAttributeTool;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;

public class RenameAttributeToolTest {

    @Test
    public void happyPath_catalogAttributeRename() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        CatalogAttribute attr = mock(CatalogAttribute.class);
        when(attr.getName()).thenReturn("Article");

        EList<CatalogAttribute> attrs = new BasicEList<>(List.of(attr));
        Catalog catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(catalog.getAttributes()).thenReturn(attrs);

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject) catalog);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        RenameAttributeTool tool = new RenameAttributeTool(() -> root, executor, new MdObjectLocator());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "fqn", "Catalog.Goods",
                "oldName", "Article",
                "newName", "SKU"));

        assertEquals("Catalog.Goods", result.get("fqn"));
        assertEquals("Attribute", result.get("role"));
        assertEquals("Article", result.get("oldName"));
        assertEquals("SKU", result.get("newName"));
        verify(attr).setName("SKU");
    }

    @Test(expected = ToolException.class)
    public void attributeNotFound_throwsToolException() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        // Catalog with empty attributes list
        EList<CatalogAttribute> attrs = new BasicEList<>();
        Catalog catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(catalog.getAttributes()).thenReturn(attrs);

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject) catalog);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        new RenameAttributeTool(() -> root, executor, new MdObjectLocator())
                .call(Map.of("project", "Demo", "fqn", "Catalog.Goods",
                        "oldName", "NonExisting", "newName", "X"));
    }
}
