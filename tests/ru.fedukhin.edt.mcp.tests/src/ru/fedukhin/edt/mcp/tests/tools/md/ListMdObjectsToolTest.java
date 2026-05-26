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
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.ListMdObjectsTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;

public class ListMdObjectsToolTest {

    @Test
    public void returnsAllCatalogs_whenKindFilterIsCatalog() throws Exception {
        // --- project setup ---
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        // --- BM mocks ---
        IBmModelManager bm = mock(IBmModelManager.class);
        IConfigurationProvider cfgP = mock(IConfigurationProvider.class);
        IBmTransaction txn = mock(IBmTransaction.class);
        Configuration cfg = mock(Configuration.class,
                withSettings().extraInterfaces(IBmObject.class));
        Catalog goods = mock(Catalog.class);
        when(goods.getName()).thenReturn("Goods");
        EList<Catalog> catalogs = new BasicEList<>(List.of(goods));
        when(cfg.getCatalogs()).thenReturn(catalogs);
        when(txn.getTopObjectByFqn("Configuration")).thenReturn((IBmObject) cfg);

        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(1);
            task.execute(txn);
            return null;
        }).when(bm).executeReadOnlyTask(eq(project), any());

        // --- invoke ---
        ListMdObjectsTool tool = new ListMdObjectsTool(() -> root, bm, cfgP, new MdObjectRegistry());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of("project", "Demo", "kind", "Catalog"));

        // --- assert ---
        List<?> objects = (List<?>) result.get("objects");
        assertEquals(1, objects.size());
        Map<?, ?> entry = (Map<?, ?>) objects.get(0);
        assertEquals("Catalog", entry.get("kind"));
        assertEquals("Goods", entry.get("name"));
        assertEquals("Catalog.Goods", entry.get("fqn"));
    }

    @Test(expected = ToolException.class)
    public void projectNotFoundThrows() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject missing = mock(IProject.class);
        when(missing.exists()).thenReturn(false);
        when(root.getProject("Missing")).thenReturn(missing);

        new ListMdObjectsTool(() -> root, mock(IBmModelManager.class),
                mock(IConfigurationProvider.class), new MdObjectRegistry())
                .call(Map.of("project", "Missing"));
    }
}
