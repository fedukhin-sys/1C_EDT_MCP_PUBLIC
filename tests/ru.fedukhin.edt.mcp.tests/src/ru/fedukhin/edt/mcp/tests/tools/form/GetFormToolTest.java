package ru.fedukhin.edt.mcp.tests.tools.form;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.form.GetFormTool;
import ru.fedukhin.edt.mcp.tools.form.internal.FormReader;
import ru.fedukhin.edt.mcp.tools.form.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tests.tools.form.GetFormItemToolTest.StubBmForm;
import ru.fedukhin.edt.mcp.tests.tools.form.GetFormItemToolTest.StubBmParent;
import ru.fedukhin.edt.mcp.tests.tools.form.GetFormItemToolTest.StubItem;
import java.util.Collections;

public class GetFormToolTest {

    private static IProject projectMock(String name, IWorkspaceRoot root) {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(true);
        when(p.getName()).thenReturn(name);
        when(root.getProject(name)).thenReturn(p);
        return p;
    }

    private static void captureAndRunTask(IBmModelManager bm, IProject project, IBmTransaction txn) {
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(1);
            task.execute(txn);
            return null;
        }).when(bm).executeReadOnlyTask(eq(project), any());
    }

    // -----------------------------------------------------------------------
    // Test 1: happy-path — parent traversal returns form
    // -----------------------------------------------------------------------

    @Test
    public void happyPath_returnsFqnAndName() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);

        IBmModelManager bm = mock(IBmModelManager.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        // Stage 6 fix: form discovered via parent.getForms() traversal.
        StubBmForm form = new StubBmForm(Collections.emptyList(), "ListForm");
        StubBmParent parent = new StubBmParent(List.of(form));
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn(parent);

        captureAndRunTask(bm, project, txn);

        MdObjectLocator locator = new MdObjectLocator();
        GetFormTool tool = new GetFormTool(() -> root, bm, locator, new FormReader());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "Catalog.Goods.Form.ListForm"));

        assertEquals("Catalog.Goods.Form.ListForm", result.get("fqn"));
        assertEquals("ListForm", result.get("name"));
        assertNotNull(result.get("items"));
        assertNotNull(result.get("tree"));
        assertNotNull(result.get("attributes"));
        assertNotNull(result.get("commands"));
        assertNotNull(result.get("hasModule"));
    }

    // -----------------------------------------------------------------------
    // Test 2: parent missing → ToolException
    // -----------------------------------------------------------------------

    @Test(expected = ToolException.class)
    public void parentNotFound_throwsToolException() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        when(txn.getTopObjectByFqn("Catalog.NoSuch")).thenReturn(null);
        captureAndRunTask(bm, project, txn);

        new GetFormTool(() -> root, bm, new MdObjectLocator(), new FormReader())
                .call(Map.of("project", "Demo", "fqn", "Catalog.NoSuch.Form.NoForm"));
    }

    // -----------------------------------------------------------------------
    // Test 3: form name not in parent.getForms() → ToolException
    // -----------------------------------------------------------------------

    @Test(expected = ToolException.class)
    public void formNotInParent_throwsToolException() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        StubBmParent parent = new StubBmParent(Collections.emptyList());
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn(parent);
        captureAndRunTask(bm, project, txn);

        new GetFormTool(() -> root, bm, new MdObjectLocator(), new FormReader())
                .call(Map.of("project", "Demo", "fqn", "Catalog.Goods.Form.NoSuch"));
    }

    // -----------------------------------------------------------------------
    // Test 4: form with no items → items=[], tree=[]
    // -----------------------------------------------------------------------

    @Test
    public void formWithNoItems_returnsEmptyLists() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        StubBmForm emptyForm = new StubBmForm(Collections.emptyList(), "F");
        StubBmParent parent = new StubBmParent(List.of(emptyForm));
        when(txn.getTopObjectByFqn("Catalog.Empty")).thenReturn(parent);
        captureAndRunTask(bm, project, txn);

        GetFormTool tool = new GetFormTool(() -> root, bm, new MdObjectLocator(), new FormReader());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "Catalog.Empty.Form.F"));

        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) result.get("items");
        @SuppressWarnings("unchecked")
        List<?> tree  = (List<?>) result.get("tree");
        assertTrue(items.isEmpty());
        assertTrue(tree.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Test 5: CommonForm fqn → top-object lookup path
    // -----------------------------------------------------------------------

    @Test
    public void commonForm_usesTopObjectLookup() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        StubBmForm form = new StubBmForm(Collections.emptyList(), "Selector");
        when(txn.getTopObjectByFqn("CommonForm.Selector")).thenReturn(form);
        captureAndRunTask(bm, project, txn);

        GetFormTool tool = new GetFormTool(() -> root, bm, new MdObjectLocator(), new FormReader());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "CommonForm.Selector"));

        assertEquals("CommonForm.Selector", result.get("fqn"));
        assertEquals("Selector", result.get("name"));
    }

    // -----------------------------------------------------------------------
    // Test 6: malformed form fqn → ToolException
    // -----------------------------------------------------------------------

    @Test(expected = ToolException.class)
    public void malformedFqn_throwsToolException() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IBmTransaction txn = mock(IBmTransaction.class);
        captureAndRunTask(bm, project, txn);

        new GetFormTool(() -> root, bm, new MdObjectLocator(), new FormReader())
                .call(Map.of("project", "Demo", "fqn", "Catalog.X"));
    }
}
