package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalog;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalogJson;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

public class PiiCatalogJsonTest {
    @Test public void roundTrip() {
        PiiCatalog c = PiiCatalog.builder()
            .object("Справочник.ФизическиеЛица", Sensitivity.PERSONAL)
            .attribute("Справочник.Контрагенты", "ИНН", Sensitivity.COUNTERPARTY)
            .build();
        String json = PiiCatalogJson.write(c, "2026-07-02T10:00:00");
        assertTrue(json.contains("\"version\""));
        PiiCatalog back = PiiCatalogJson.read(json);
        assertEquals(Sensitivity.PERSONAL, back.forObject("Справочник.ФизическиеЛица"));
        assertEquals(Sensitivity.COUNTERPARTY, back.forAttribute("Справочник.Контрагенты", "ИНН"));
    }

    @Test public void readEmptyOrMissingSectionsIsSafe() {
        PiiCatalog back = PiiCatalogJson.read("{\"version\":1}");
        assertTrue(back.isEmpty());
    }
}
