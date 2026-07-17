package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectBorrower;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectKind;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;

public class MdObjectRegistryTest {

    @Test
    public void registryHasAllCreatableKinds() {
        MdObjectRegistry r = new MdObjectRegistry();
        for (String name : new String[]{
                "Catalog", "Document", "InformationRegister", "AccumulationRegister",
                "Constant", "Enum", "CommonModule", "Role", "Subsystem",
                "DataProcessor", "Report"}) {
            assertNotNull("missing kind: " + name, r.get(name));
            assertTrue("исторически создаваемый kind обязан остаться creatable: " + name,
                    r.get(name).creatable());
        }
    }

    /**
     * Рассинхрон реестров и был корнем находки аудита: borrow умеет 19 kind'ов, а registry знал
     * 11, из-за чего заимствованные планы счетов и видов расчёта, Task, BusinessProcess,
     * ExchangePlan, DocumentJournal, CommonForm и CommonPicture были невидимы для
     * list_md_objects и недоступны get_md_object («unknown kind»).
     */
    @Test
    public void registryCoversEveryBorrowableKind() {
        MdObjectRegistry r = new MdObjectRegistry();
        for (String name : MdObjectBorrower.supportedKinds()) {
            assertNotNull("kind заимствуется, но неизвестен реестру: " + name, r.get(name));
        }
    }

    /** Заимствовать можно всё, а создавать с нуля — только проверенное подмножество. */
    @Test
    public void kindsRequiringCompanionArtifactsAreNotCreatable() {
        MdObjectRegistry r = new MdObjectRegistry();
        for (String name : new String[]{
                "BusinessProcess", "Task", "ChartOfAccounts", "ChartOfCalculationTypes",
                "ChartOfCharacteristicTypes", "ExchangePlan", "DocumentJournal",
                "CommonForm", "CommonPicture"}) {
            assertNotNull("missing kind: " + name, r.get(name));
            assertFalse("kind не проверен для create_md_object и обязан отбиваться: " + name,
                    r.get(name).creatable());
        }
    }

    /** У этих kind'ов есть getAttributes() — add_attribute на них обязан работать. */
    @Test
    public void borrowedKindsWithAttributes_supportAttributes() {
        MdObjectRegistry r = new MdObjectRegistry();
        for (String name : new String[]{
                "BusinessProcess", "Task", "ChartOfAccounts", "ChartOfCalculationTypes",
                "ChartOfCharacteristicTypes", "ExchangePlan"}) {
            assertTrue("kind обязан поддерживать реквизиты: " + name,
                    r.get(name).supportsAttributes());
        }
    }

    @Test
    public void kindsWithAttributes() {
        MdObjectRegistry r = new MdObjectRegistry();
        assertTrue(r.get("Catalog").supportsAttributes());
        assertTrue(r.get("Document").supportsAttributes());
        assertTrue(r.get("InformationRegister").supportsAttributes());
        assertTrue(r.get("AccumulationRegister").supportsAttributes());
    }

    @Test
    public void kindsWithoutAttributes() {
        MdObjectRegistry r = new MdObjectRegistry();
        for (String name : new String[]{
                "Constant", "Enum", "CommonModule", "Role", "Subsystem",
                "DataProcessor", "Report"}) {
            assertFalse("expected no attribute support: " + name, r.get(name).supportsAttributes());
        }
    }

    @Test
    public void commonModuleHasModuleFile() {
        MdObjectRegistry r = new MdObjectRegistry();
        MdObjectKind cm = r.get("CommonModule");
        assertTrue(cm.hasModule());
        assertEquals("Module.bsl", cm.moduleFileName());
        assertEquals("CommonModules", cm.folderName());
        assertTrue("CommonModule должен создавать пустой Module.bsl физически",
                cm.requireEmptyModuleFile());
    }

    @Test
    public void dataKindsHaveLazyModuleFile() {
        // Bug #4 fix: Document/Catalog/DataProcessor/Report/Register-kinds возвращают
        // modulePath из create_md_object, но файл не создаётся физически (lazy).
        MdObjectRegistry r = new MdObjectRegistry();
        assertModule(r.get("Catalog"),               "Catalogs",              "ObjectModule.bsl");
        assertModule(r.get("Document"),              "Documents",             "ObjectModule.bsl");
        assertModule(r.get("DataProcessor"),         "DataProcessors",        "ObjectModule.bsl");
        assertModule(r.get("Report"),                "Reports",               "ObjectModule.bsl");
        assertModule(r.get("AccumulationRegister"),  "AccumulationRegisters", "RecordSetModule.bsl");
        assertModule(r.get("InformationRegister"),   "InformationRegisters",  "RecordSetModule.bsl");
    }

    private static void assertModule(MdObjectKind k, String folder, String file) {
        assertTrue("kind должен иметь модуль: " + k.name(), k.hasModule());
        assertEquals(folder, k.folderName());
        assertEquals(file,   k.moduleFileName());
        assertFalse("kind не должен создавать пустой .bsl: " + k.name(),
                k.requireEmptyModuleFile());
    }

    @Test
    public void kindsWithoutModuleFile() {
        MdObjectRegistry r = new MdObjectRegistry();
        for (String name : new String[]{"Constant", "Enum", "Role", "Subsystem"}) {
            assertFalse("expected no module file: " + name, r.get(name).hasModule());
            assertNull(r.get(name).moduleFileName());
        }
    }

    @Test
    public void unknownKindReturnsNull() {
        MdObjectRegistry r = new MdObjectRegistry();
        assertNull(r.get("Frobnicator"));
    }

    /**
     * Единый источник правды по папкам: маппинг был скопирован в 7 инструментов, и копии
     * разъезжались. Отдельно — наивная плюрализация в get_form, дававшая «BusinessProcesss».
     */
    @Test
    public void folderName_staticLookup_matchesRegistryAndHasNoNaivePluralisation() {
        assertEquals("Catalogs",                    MdObjectRegistry.folderName("Catalog"));
        assertEquals("BusinessProcesses",           MdObjectRegistry.folderName("BusinessProcess"));
        assertEquals("ChartsOfAccounts",            MdObjectRegistry.folderName("ChartOfAccounts"));
        assertEquals("ChartsOfCalculationTypes",    MdObjectRegistry.folderName("ChartOfCalculationTypes"));
        assertEquals("ChartsOfCharacteristicTypes", MdObjectRegistry.folderName("ChartOfCharacteristicTypes"));
        assertNull("неизвестный kind не должен давать выдуманную папку",
                MdObjectRegistry.folderName("Frobnicator"));

        MdObjectRegistry r = new MdObjectRegistry();
        for (MdObjectKind k : r.allKinds()) {
            assertEquals("static folderName обязан совпадать с реестром: " + k.name(),
                    k.folderName(), MdObjectRegistry.folderName(k.name()));
        }
    }

    /**
     * У {@link MdObjectBorrower} свой KINDS с дополнительными данными (ns-prefix, extension-тип),
     * поэтому карта осталась отдельной. Имена папок в ней обязаны совпадать с реестром —
     * иначе borrow положит объект не туда, где его потом ищут остальные инструменты.
     */
    @Test
    public void borrowerFolderNames_agreeWithRegistry() {
        for (String kind : MdObjectBorrower.supportedKinds()) {
            assertEquals("папка borrow'а разошлась с реестром для kind: " + kind,
                    MdObjectRegistry.folderName(kind), MdObjectBorrower.folderOf(kind));
        }
    }
}
