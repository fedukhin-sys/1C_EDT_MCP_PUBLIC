package ru.fedukhin.edt.mcp.tests.tools.form;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.form.internal.FormRegistry;

public class FormRegistryTest {

    @Test
    public void supportedKindsContainsSix() {
        FormRegistry r = new FormRegistry();
        assertEquals(6, r.supportedKinds().size());
        for (String kind : new String[]{
                "Catalog", "Document", "InformationRegister",
                "AccumulationRegister", "DataProcessor", "Report"}) {
            assertTrue("missing kind: " + kind, r.supportedKinds().contains(kind));
        }
    }

    @Test
    public void accessorForCatalogReturnsGetForms() {
        FormRegistry r = new FormRegistry();
        assertEquals("getForms", r.accessorFor("Catalog"));
        assertEquals("getForms", r.accessorFor("Document"));
        assertEquals("getForms", r.accessorFor("DataProcessor"));
    }

    @Test
    public void accessorForRoleReturnsNull() {
        FormRegistry r = new FormRegistry();
        assertNull(r.accessorFor("Role"));
        assertNull(r.accessorFor("CommonModule"));
        assertNull(r.accessorFor("Nonexistent"));
    }
}
