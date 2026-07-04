package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.BslTypeNames;

public class BslTypeNamesTest {
    @Test public void refAndObjectTypesMapToFullName() {
        assertEquals("Справочник.Контрагенты",
            BslTypeNames.objectFullName("СправочникСсылка.Контрагенты").orElse(null));
        assertEquals("Справочник.Контрагенты",
            BslTypeNames.objectFullName("СправочникОбъект.Контрагенты").orElse(null));
        assertEquals("Документ.РеализацияТоваров",
            BslTypeNames.objectFullName("ДокументСсылка.РеализацияТоваров").orElse(null));
    }
    @Test public void primitivesAreEmpty() {
        assertTrue(BslTypeNames.objectFullName("Строка").isEmpty());
        assertTrue(BslTypeNames.objectFullName("Число").isEmpty());
        assertTrue(BslTypeNames.objectFullName(null).isEmpty());
    }
}
