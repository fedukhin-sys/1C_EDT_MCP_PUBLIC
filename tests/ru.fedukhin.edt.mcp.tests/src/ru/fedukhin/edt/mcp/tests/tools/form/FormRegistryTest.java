package ru.fedukhin.edt.mcp.tests.tools.form;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.form.internal.FormRegistry;

public class FormRegistryTest {

    /**
     * Формы есть не только у шести «основных» контейнеров: раньше реестр знал именно их, и
     * list_forms молча не показывал формы бизнес-процессов, задач, планов счетов/видов
     * расчёта/характеристик, планов обмена, перечислений и журналов документов, а с parentFqn
     * отбивал их как «не поддерживает формы».
     */
    @Test
    public void supportedKindsCoverEveryFormBearingKind() {
        FormRegistry r = new FormRegistry();
        for (String kind : new String[]{
                "Catalog", "Document", "InformationRegister", "AccumulationRegister",
                "DataProcessor", "Report", "BusinessProcess", "Task",
                "ChartOfAccounts", "ChartOfCalculationTypes", "ChartOfCharacteristicTypes",
                "ExchangePlan", "Enum", "DocumentJournal"}) {
            assertTrue("missing kind: " + kind, r.supportedKinds().contains(kind));
        }
        assertEquals(14, r.supportedKinds().size());
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
