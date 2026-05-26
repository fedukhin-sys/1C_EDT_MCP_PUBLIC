package ru.fedukhin.edt.mcp.tests.tools.form;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.form.internal.FormReader;

/**
 * Tests for FormReader using minimal stub EObjects that expose real Java methods
 * (not Mockito proxies — reflection on proxy doesn't see the declared methods).
 */
public class FormReaderTest {

    // ---------------------------------------------------------------------------
    // Minimal stub EObject — delegates everything to ArrayList for getItems()
    // ---------------------------------------------------------------------------

    private static abstract class StubEObject implements EObject {
        @Override public EClass eClass() { return null; }
        @Override public Resource eResource() { return null; }
        @Override public EObject eContainer() { return null; }
        @Override public EStructuralFeature eContainingFeature() { return null; }
        @Override public EReference eContainmentFeature() { return null; }
        @Override public EList<EObject> eContents() { return new org.eclipse.emf.common.util.BasicEList<>(); }
        @Override public TreeIterator<EObject> eAllContents() { return null; }
        @Override public boolean eIsProxy() { return false; }
        @Override public EList<EObject> eCrossReferences() { return new org.eclipse.emf.common.util.BasicEList<>(); }
        @Override public Object eGet(EStructuralFeature f) { return null; }
        @Override public Object eGet(EStructuralFeature f, boolean r) { return null; }
        @Override public void eSet(EStructuralFeature f, Object v) {}
        @Override public boolean eIsSet(EStructuralFeature f) { return false; }
        @Override public void eUnset(EStructuralFeature f) {}
        @Override public Object eInvoke(EOperation o, EList<?> a) { return null; }
        @Override public EList<Adapter> eAdapters() { return new org.eclipse.emf.common.util.BasicEList<>(); }
        @Override public boolean eDeliver() { return false; }
        @Override public void eSetDeliver(boolean d) {}
        @Override public void eNotify(Notification n) {}
    }

    /** Item with a name and optional children (exposed via getItems()). */
    private static class StubItem extends StubEObject {
        private final String name;
        private final List<EObject> children;

        StubItem(String name, List<EObject> children) {
            this.name = name;
            this.children = children;
        }

        StubItem(String name) { this(name, Collections.emptyList()); }

        public String getName() { return name; }
        public List<EObject> getItems() { return children; }
    }

    /** Minimal form stub with items, attributes, and commands lists. */
    private static class StubForm extends StubEObject {
        private final List<EObject> items;
        private final List<EObject> attributes;
        private final List<EObject> formCommands;

        StubForm(List<EObject> items, List<EObject> attributes, List<EObject> formCommands) {
            this.items = items;
            this.attributes = attributes;
            this.formCommands = formCommands;
        }

        public List<EObject> getItems()        { return items; }
        public List<EObject> getAttributes()   { return attributes; }
        public List<EObject> getFormCommands() { return formCommands; }
    }

    /** Attribute stub with name and a stub mcore TypeDescription as valueType. */
    private static class StubAttr extends StubEObject {
        private final String name;
        private final Object valueType;

        StubAttr(String name, Object valueType) { this.name = name; this.valueType = valueType; }

        public String getName()      { return name; }
        public Object getValueType() { return valueType; }
    }

    /** Stub mcore TypeDescription — exposes getTypes() and no qualifiers. */
    private static class StubTypeDesc extends StubEObject {
        private final List<EObject> types = new ArrayList<>();
        StubTypeDesc(String... typeNames) {
            for (String n : typeNames) {
                types.add(new StubTypeItem(n));
            }
        }
        public List<EObject> getTypes()     { return types; }
        public Object getStringQualifiers() { return null; }
        public Object getNumberQualifiers() { return null; }
    }

    /** Stub mcore TypeItem — exposes getName(). */
    private static class StubTypeItem extends StubEObject {
        private final String name;
        StubTypeItem(String name) { this.name = name; }
        public String getName() { return name; }
    }

    /** Command stub with name and title (String). */
    private static class StubCmd extends StubEObject {
        private final String name;
        private final Map<String, String> title;

        StubCmd(String name, String title) {
            this.name = name;
            this.title = title != null ? Map.of("ru", title) : null;
        }

        public String getName()             { return name; }
        public Map<String, String> getTitle() { return title; }
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    public void readItemsFlat_threeLevelTree_returnsThreePaths() {
        // root → groupA → leaf
        StubItem leaf   = new StubItem("Leaf");
        StubItem groupA = new StubItem("GroupA", List.of(leaf));
        StubForm form   = new StubForm(List.of(groupA), List.of(), List.of());

        FormReader reader = new FormReader();
        List<Map<String, Object>> flat = reader.readItemsFlat(form);

        assertEquals(2, flat.size());
        assertEquals("GroupA", flat.get(0).get("path"));
        assertEquals("GroupA/Leaf", flat.get(1).get("path"));
    }

    @Test
    public void readItemsTree_twoLevelTree_returnsNestedChildren() {
        StubItem child1 = new StubItem("Child1");
        StubItem child2 = new StubItem("Child2");
        StubItem parent = new StubItem("Parent", List.of(child1, child2));
        StubForm form   = new StubForm(List.of(parent), List.of(), List.of());

        FormReader reader = new FormReader();
        List<Map<String, Object>> tree = reader.readItemsTree(form);

        assertEquals(1, tree.size());
        Map<?, ?> parentMap = (Map<?, ?>) tree.get(0);
        assertEquals("Parent", parentMap.get("name"));
        List<?> children = (List<?>) parentMap.get("children");
        assertNotNull(children);
        assertEquals(2, children.size());
    }

    @Test
    public void readAttributes_twoAttrs_returnsTwoEntries() {
        StubAttr attr1 = new StubAttr("Object", new StubTypeDesc("CatalogRef.Goods"));
        StubAttr attr2 = new StubAttr("Flag",   new StubTypeDesc("Boolean"));
        StubForm form  = new StubForm(List.of(), List.of(attr1, attr2), List.of());

        FormReader reader = new FormReader();
        List<Map<String, Object>> attrs = reader.readAttributes(form);

        assertEquals(2, attrs.size());
        assertEquals("Object", attrs.get(0).get("name"));
        assertEquals("CatalogRef.Goods", attrs.get(0).get("type"));
        assertEquals("Flag", attrs.get(1).get("name"));
    }

    @Test
    public void readCommands_oneCommand_returnsIt() {
        StubCmd cmd  = new StubCmd("SaveAndClose", "Записать и закрыть");
        StubForm form = new StubForm(List.of(), List.of(), List.of(cmd));

        FormReader reader = new FormReader();
        List<Map<String, Object>> cmds = reader.readCommands(form);

        assertEquals(1, cmds.size());
        assertEquals("SaveAndClose", cmds.get(0).get("name"));
        assertEquals("Записать и закрыть", cmds.get(0).get("title"));
    }

    @Test
    public void readItemsFlat_emptyForm_returnsEmptyList() {
        StubForm form = new StubForm(List.of(), List.of(), List.of());

        FormReader reader = new FormReader();
        List<Map<String, Object>> flat = reader.readItemsFlat(form);

        assertTrue(flat.isEmpty());
    }
}
