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
import org.eclipse.emf.common.util.EMap;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.GetMdObjectTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringFormatter;

public class GetMdObjectToolTest {

    private GetMdObjectTool makeToolWithBm(IBmModelManager bm, IBmTransaction txn,
                                           IProject project, IWorkspaceRoot root) {
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

    @Test
    public void happyPath_catalogWithOneAttribute() throws Exception {
        // Setup project
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        // Setup Catalog mock
        Catalog catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(catalog.getName()).thenReturn("Goods");
        EMap<String, String> synonymMap = new BasicEMap<>();
        synonymMap.put("ru", "Товары");
        when(catalog.getSynonym()).thenReturn(synonymMap);
        when(catalog.getComment()).thenReturn("Test catalog");

        // Setup CatalogAttribute
        CatalogAttribute attr = mock(CatalogAttribute.class);
        when(attr.getName()).thenReturn("Article");
        when(attr.getSynonym()).thenReturn(new BasicEMap<>());
        when(attr.getComment()).thenReturn("");
        EList<CatalogAttribute> attrs = new BasicEList<>(List.of(attr));
        when(catalog.getAttributes()).thenReturn(attrs);

        // Setup BM
        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject) catalog);

        IBmModelManager bm = mock(IBmModelManager.class);
        GetMdObjectTool tool = makeToolWithBm(bm, txn, project, root);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "Catalog.Goods"));

        assertEquals("Catalog", result.get("kind"));
        assertEquals("Goods", result.get("name"));
        assertEquals("Catalog.Goods", result.get("fqn"));
        assertEquals("Товары", result.get("synonym"));
        assertEquals("Test catalog", result.get("comment"));
        assertNotNull(result.get("attributes"));
        List<?> attrList = (List<?>) result.get("attributes");
        assertEquals(1, attrList.size());
        Map<?, ?> attrEntry = (Map<?, ?>) attrList.get(0);
        assertEquals("Article", attrEntry.get("name"));
        assertEquals("Attribute", attrEntry.get("role"));
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

        IBmModelManager bm = mock(IBmModelManager.class);
        GetMdObjectTool tool = makeToolWithBm(bm, txn, project, root);

        tool.call(Map.of("project", "Demo", "fqn", "Catalog.Missing"));
    }
}
