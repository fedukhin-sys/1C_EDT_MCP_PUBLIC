package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalog;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

public class PiiCatalogTest {

    @Test public void lookupObjectAndAttribute() {
        PiiCatalog c = PiiCatalog.builder()
            .object("Справочник.ФизическиеЛица", Sensitivity.PERSONAL)
            .object("Справочник.Контрагенты", Sensitivity.COUNTERPARTY)
            .attribute("Справочник.Контрагенты", "ИНН", Sensitivity.COUNTERPARTY)
            .build();
        assertEquals(Sensitivity.PERSONAL, c.forObject("Справочник.ФизическиеЛица"));
        assertEquals(Sensitivity.NONE, c.forObject("Справочник.Валюты"));
        assertEquals(Sensitivity.COUNTERPARTY, c.forAttribute("Справочник.Контрагенты", "ИНН"));
        assertEquals(Sensitivity.NONE, c.forAttribute("Справочник.Контрагенты", "Наименование"));
    }

    @Test public void mergeIsFailClosedMostSensitiveWins() {
        PiiCatalog a = PiiCatalog.builder().object("Справочник.Х", Sensitivity.COUNTERPARTY).build();
        PiiCatalog b = PiiCatalog.builder().object("Справочник.Х", Sensitivity.SPECIAL).build();
        PiiCatalog m = PiiCatalog.merge(List.of(a, b));
        assertEquals(Sensitivity.SPECIAL, m.forObject("Справочник.Х"));
    }

    @Test public void sensitivityFlags() {
        assertTrue(Sensitivity.SPECIAL.fullHide());
        assertTrue(Sensitivity.BIOMETRIC.fullHide());
        assertFalse(Sensitivity.PERSONAL.fullHide());
        assertTrue(Sensitivity.PERSONAL.isSensitive());
        assertFalse(Sensitivity.NONE.isSensitive());
    }
}
