package ru.fedukhin.edt.mcp.tests.tools.form;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.form.GetFormItemTool;
import ru.fedukhin.edt.mcp.tools.form.internal.FormReader;
import ru.fedukhin.edt.mcp.tools.form.internal.MdObjectLocator;

public class GetFormItemToolTest {

    // -----------------------------------------------------------------------
    // Stub EObject base
    // -----------------------------------------------------------------------

    public static class StubEO implements EObject {
        @Override public EClass eClass() { return null; }
        @Override public Resource eResource() { return null; }
        @Override public EObject eContainer() { return null; }
        @Override public EStructuralFeature eContainingFeature() { return null; }
        @Override public EReference eContainmentFeature() { return null; }
        @Override public EList<EObject> eContents() { return new BasicEList<>(); }
        @Override public TreeIterator<EObject> eAllContents() { return null; }
        @Override public boolean eIsProxy() { return false; }
        @Override public EList<EObject> eCrossReferences() { return new BasicEList<>(); }
        @Override public Object eGet(EStructuralFeature f) { return null; }
        @Override public Object eGet(EStructuralFeature f, boolean r) { return null; }
        @Override public void eSet(EStructuralFeature f, Object v) {}
        @Override public boolean eIsSet(EStructuralFeature f) { return false; }
        @Override public void eUnset(EStructuralFeature f) {}
        @Override public Object eInvoke(EOperation o, EList<?> a) { return null; }
        @Override public EList<Adapter> eAdapters() { return new BasicEList<>(); }
        @Override public boolean eDeliver() { return false; }
        @Override public void eSetDeliver(boolean d) {}
        @Override public void eNotify(Notification n) {}
    }

    /** Leaf form item with a name. */
    public static class StubItem extends StubEO {
        private final String name;
        private final List<EObject> children;
        public StubItem(String name, List<EObject> children) { this.name = name; this.children = children; }
        public StubItem(String name) { this(name, Collections.emptyList()); }
        public String getName()         { return name; }
        public List<EObject> getItems() { return children; }
    }

    /**
     * Form stub that is also an IBmObject (all IBmObject methods throw/return null).
     * Stage 6 fix: form имеет имя через getName(), которое проверяется при parent
     * traversal в MdObjectLocator.findForm.
     */
    public static class StubBmForm extends StubEO implements IBmObject {
        private final List<EObject> items;
        private final String name;
        public StubBmForm(List<EObject> items, String name) { this.items = items; this.name = name; }
        /** Backward-compat ctor for tests that don't care about form name. */
        public StubBmForm(List<EObject> items) { this(items, "Form"); }
        public List<EObject> getItems() { return items; }
        public String getName() { return name; }

        @Override public long bmGetId() { return 0; }
        @Override public IBmEngine bmGetEngine() { return null; }
        @Override public IBmNamespace bmGetNamespace() { return null; }
        @Override public IBmPlatformTransaction bmGetPlatformTransaction() { return null; }
        @Override public IBmTransaction bmGetTransaction() { return null; }
        @Override public boolean bmIsTop() { return true; }
        @Override public String bmGetFqn() { return null; }
        @Override public IBmObject bmGetTopObject() { return this; }
        @Override public org.eclipse.emf.common.util.URI bmGetUri() { return null; }
        @Override public String bmGetUriAsString() { return null; }
        @Override public int bmGetResourceId() { return 0; }
        @Override public java.util.Collection<IBmCrossReference> bmGetReferences() { return List.of(); }
        @Override public String bmGetProperty(String key) { return null; }
        @Override public java.util.Map<String, String> bmGetProperties() { return Map.of(); }
        @Override public void bmSetProperty(String key, String val) {}
        @Override public boolean bmIsTransient() { return false; }
        @Override public boolean bmMatchSingleReference(EReference ref, EObject obj) { return false; }
    }

    /**
     * Parent MdObject stub with getForms() collection — top-object that форма
     * висит inline. Stage 6 fix: MdObjectLocator.findForm делает
     * {@code txn.getTopObjectByFqn(parentFqn)} → parent → walk getForms().
     */
    public static class StubBmParent extends StubEO implements IBmObject {
        private final List<EObject> forms;
        public StubBmParent(List<EObject> forms) { this.forms = forms; }
        public List<EObject> getForms() { return forms; }

        @Override public long bmGetId() { return 0; }
        @Override public IBmEngine bmGetEngine() { return null; }
        @Override public IBmNamespace bmGetNamespace() { return null; }
        @Override public IBmPlatformTransaction bmGetPlatformTransaction() { return null; }
        @Override public IBmTransaction bmGetTransaction() { return null; }
        @Override public boolean bmIsTop() { return true; }
        @Override public String bmGetFqn() { return null; }
        @Override public IBmObject bmGetTopObject() { return this; }
        @Override public org.eclipse.emf.common.util.URI bmGetUri() { return null; }
        @Override public String bmGetUriAsString() { return null; }
        @Override public int bmGetResourceId() { return 0; }
        @Override public java.util.Collection<IBmCrossReference> bmGetReferences() { return List.of(); }
        @Override public String bmGetProperty(String key) { return null; }
        @Override public java.util.Map<String, String> bmGetProperties() { return Map.of(); }
        @Override public void bmSetProperty(String key, String val) {}
        @Override public boolean bmIsTransient() { return false; }
        @Override public boolean bmMatchSingleReference(EReference ref, EObject obj) { return false; }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
    // Test 1: valid path returns leaf
    // -----------------------------------------------------------------------

    @Test
    public void validPath_returnsLeaf() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        StubItem leaf   = new StubItem("FieldCode");
        StubItem group  = new StubItem("GroupHeader", List.of(leaf));
        StubBmForm form = new StubBmForm(List.of(group), "ItemForm");
        StubBmParent parent = new StubBmParent(List.of(form));

        // Stage 6 fix: parent lookup, then walk getForms() to find "ItemForm".
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn(parent);
        captureAndRunTask(bm, project, txn);

        GetFormItemTool tool = new GetFormItemTool(() -> root, bm, new MdObjectLocator(), new FormReader());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo",
                       "fqn", "Catalog.Goods.Form.ItemForm",
                       "itemPath", "GroupHeader/FieldCode"));

        assertEquals("FieldCode", result.get("name"));
        assertEquals("GroupHeader/FieldCode", result.get("itemPath"));
        assertEquals(0, result.get("childCount"));
    }

    // -----------------------------------------------------------------------
    // Test 2: invalid path throws ToolException
    // -----------------------------------------------------------------------

    @Test(expected = ToolException.class)
    public void invalidPath_throwsToolException() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        StubBmForm form = new StubBmForm(List.of(new StubItem("OnlyGroup")), "ItemForm");
        StubBmParent parent = new StubBmParent(List.of(form));
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn(parent);
        captureAndRunTask(bm, project, txn);

        new GetFormItemTool(() -> root, bm, new MdObjectLocator(), new FormReader())
                .call(Map.of("project", "Demo",
                             "fqn", "Catalog.Goods.Form.ItemForm",
                             "itemPath", "DoesNotExist/Child"));
    }

    // -----------------------------------------------------------------------
    // Test 3: intermediate group path returns group with childCount > 0
    // -----------------------------------------------------------------------

    @Test
    public void intermediatePath_returnsGroupWithChildren() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        StubItem child1 = new StubItem("Field1");
        StubItem child2 = new StubItem("Field2");
        StubItem group  = new StubItem("GroupHeader", List.of(child1, child2));
        StubBmForm form = new StubBmForm(List.of(group), "ItemForm");
        StubBmParent parent = new StubBmParent(List.of(form));

        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn(parent);
        captureAndRunTask(bm, project, txn);

        GetFormItemTool tool = new GetFormItemTool(() -> root, bm, new MdObjectLocator(), new FormReader());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo",
                       "fqn", "Catalog.Goods.Form.ItemForm",
                       "itemPath", "GroupHeader"));

        assertEquals("GroupHeader", result.get("name"));
        assertEquals(2, result.get("childCount"));
    }
}
