package ru.fedukhin.edt.mcp.tests.tools.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.tests.internal.TestModuleHeuristic;

public class TestModuleHeuristicTest {

    private final TestModuleHeuristic heuristic = new TestModuleHeuristic();

    // Test 1: detectLanguage returns "ru" for module name containing "Тесты"
    @Test
    public void detectLanguage_russianByName() {
        String lang = heuristic.detectLanguage("КаталогТесты", "");
        assertEquals("ru", lang);
    }

    // Test 2: detectLanguage returns "ru" for body containing ИсполняемыеСценарии
    @Test
    public void detectLanguage_russianByBody() {
        String body = "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\nКонецПроцедуры";
        String lang = heuristic.detectLanguage("ОбщийМодуль", body);
        assertEquals("ru", lang);
    }

    // Test 3: detectLanguage returns "en" for module name containing "Tests"
    @Test
    public void detectLanguage_englishByName() {
        String lang = heuristic.detectLanguage("CatalogTests", "");
        assertEquals("en", lang);
    }

    // Test 4: isTestModule returns true/false correctly
    @Test
    public void isTestModule_trueAndFalse() {
        assertTrue(heuristic.isTestModule("MyTests", ""));
        assertFalse(heuristic.isTestModule("MyModule", ""));
        assertTrue(heuristic.isTestModule("SomeModule",
                "Procedure ExecutableScenarios(UnitTests) Export\nEndProcedure"));
    }

    // Test 5: hasExecutableScenarios true/false
    @Test
    public void hasExecutableScenarios_trueAndFalse() {
        String withProcedure = "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\nКонецПроцедуры";
        assertTrue(heuristic.hasExecutableScenarios(withProcedure));
        assertFalse(heuristic.hasExecutableScenarios("// just a comment"));
        assertFalse(heuristic.hasExecutableScenarios(null));
    }
}
