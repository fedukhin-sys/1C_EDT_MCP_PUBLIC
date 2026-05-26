package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.RenameMdObjectTool;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;

public class RenameMdObjectToolTest {

    @Test
    public void happyPath_catalogRename() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        Catalog catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Catalog.OldName")).thenReturn((IBmObject) catalog);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        RenameMdObjectTool tool = new RenameMdObjectTool(() -> root, executor, new MdObjectLocator());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "Catalog.OldName", "newName", "NewName"));

        assertEquals("Catalog.OldName", result.get("oldFqn"));
        assertEquals("Catalog.NewName", result.get("newFqn"));
        assertEquals("Catalog", result.get("kind"));
        assertEquals("NewName", result.get("newName"));
        verify(catalog).setName("NewName");
    }

    @Test(expected = ToolException.class)
    public void fqnNotFound_throwsToolException() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Catalog.Missing")).thenReturn(null);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        new RenameMdObjectTool(() -> root, executor, new MdObjectLocator())
                .call(Map.of("project", "Demo", "fqn", "Catalog.Missing", "newName", "X"));
    }
}
