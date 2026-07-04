package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;
import ru.fedukhin.edt.mcp.tools.privacy.internal.MetadataScanner;

/** Юнит-тесты чистых эвристик {@link MetadataScanner} — без BM/workspace. */
public class MetadataScannerTest {

    @Test
    public void objectNameHeuristics() {
        assertEquals(Sensitivity.PERSONAL, MetadataScanner.classifyObject("Catalog", "ФизическиеЛица"));
        assertEquals(Sensitivity.COUNTERPARTY, MetadataScanner.classifyObject("Catalog", "Контрагенты"));
        assertEquals(Sensitivity.ORGANIZATION, MetadataScanner.classifyObject("Catalog", "Организации"));
        assertEquals(Sensitivity.PERSONAL, MetadataScanner.classifyObject("Catalog", "Пользователи"));
        assertEquals(Sensitivity.NONE, MetadataScanner.classifyObject("Catalog", "Валюты"));
    }

    @Test
    public void attributeHeuristicByNameOnly() {
        assertEquals(Sensitivity.COUNTERPARTY,
            MetadataScanner.classifyAttribute("Справочник.Контрагенты", "ИНН", "Строка"));
    }

    @Test
    public void attributeHeuristicByReferencedTypeViaResolver() {
        // тип-ссылка на чувствительный объект → наследует через typeResolver
        assertEquals(Sensitivity.PERSONAL,
            MetadataScanner.classifyAttribute("Документ.Заказ", "Ответственный", "СправочникСсылка.ФизическиеЛица",
                name -> name.equals("Справочник.ФизическиеЛица") ? Sensitivity.PERSONAL : Sensitivity.NONE));
    }
}
