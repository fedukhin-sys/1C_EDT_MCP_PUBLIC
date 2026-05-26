package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectKind;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;

public class MdObjectRegistryTest {

    @Test
    public void registryHasAllElevenKinds() {
        MdObjectRegistry r = new MdObjectRegistry();
        assertEquals(11, r.allKinds().size());                          // 10 editable + Configuration root
        for (String name : new String[]{
                "Catalog", "Document", "InformationRegister", "AccumulationRegister",
                "Constant", "Enum", "CommonModule", "Role", "Subsystem",
                "DataProcessor", "Report"}) {
            assertNotNull("missing kind: " + name, r.get(name));
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
}
