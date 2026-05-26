package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.SetMdPropertyTool;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;

public class SetMdPropertyToolTest {

    @Test
    public void setCommentOnCatalog_callsAccessorWithParent() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        Catalog catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject) catalog);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        PropertyAccessor accessor = new PropertyAccessor(mock(IV8ProjectManager.class));
        SetMdPropertyTool tool = new SetMdPropertyTool(() -> root, executor, new MdObjectLocator(), accessor);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "Catalog.Goods", "property", "comment", "value", "My comment"));

        assertEquals("Catalog.Goods", result.get("fqn"));
        assertEquals("comment", result.get("property"));
        assertEquals("My comment", result.get("value"));
        verify(catalog).setComment("My comment");
    }

    @Test
    public void setCommentOnAttribute_viaParsedPath() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        CatalogAttribute attr = mock(CatalogAttribute.class);
        when(attr.getName()).thenReturn("Code");

        EList<CatalogAttribute> attrs = new BasicEList<>(java.util.List.of(attr));
        Catalog catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(catalog.getAttributes()).thenReturn(attrs);

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject) catalog);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        PropertyAccessor accessor = new PropertyAccessor(mock(IV8ProjectManager.class));
        SetMdPropertyTool tool = new SetMdPropertyTool(() -> root, executor, new MdObjectLocator(), accessor);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "fqn", "Catalog.Goods",
                "path", "Attributes/Code",
                "property", "comment",
                "value", "attr comment"));

        assertEquals("Catalog.Goods", result.get("fqn"));
        assertEquals("Attributes/Code", result.get("path"));
        verify(attr).setComment("attr comment");
    }

    @Test(expected = ToolException.class)
    public void whitelistViolation_surfacesAsToolException() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        Catalog catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject) catalog);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        PropertyAccessor accessor = new PropertyAccessor(mock(IV8ProjectManager.class));
        SetMdPropertyTool tool = new SetMdPropertyTool(() -> root, executor, new MdObjectLocator(), accessor);

        // "server" is not whitelisted for Catalog kind — should throw ToolException
        tool.call(Map.of("project", "Demo", "fqn", "Catalog.Goods",
                "property", "server", "value", true));
    }
}
