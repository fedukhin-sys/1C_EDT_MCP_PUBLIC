package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.testrun.internal.BslRunnerTemplates;

public class BslRunnerTemplatesTest {

    @Test public void clientModuleBody_entryProcedure_callsServerAndExits() {
        String body = BslRunnerTemplates.clientModuleBody();
        assertEquals("ВыполнитьЕсли7bПараметр should appear exactly once",
            1, count(body, "Процедура ВыполнитьЕсли7bПараметр()"));
        assertTrue("must reference ПараметрЗапуска", body.contains("ПараметрЗапуска"));
        assertTrue("must reference our protocol prefix", body.contains("EDT_MCP_TESTS="));
        assertTrue("must call server runner",
            body.contains("EDT_MCP_TestRunner_Сервер.ВыполнитьТесты"));
        assertTrue("must call ЗавершитьРаботуСистемы(Ложь)",
            body.contains("ЗавершитьРаботуСистемы(Ложь)"));
    }

    @Test public void clientModuleBody_helperProcedures_areDefined() {
        // Entry procedure CALLS these — they must be defined in the same module.
        String body = BslRunnerTemplates.clientModuleBody();
        assertEquals("РазобратьСелектор must be defined exactly once",
            1, count(body, "Функция РазобратьСелектор("));
        assertEquals("РаскодироватьБазу64 must be defined exactly once",
            1, count(body, "Функция РаскодироватьБазу64("));
        assertEquals("ЗаписатьРезультат must be defined exactly once",
            1, count(body, "Процедура ЗаписатьРезультат("));
    }

    @Test public void clientModuleBody_procedureFunctionBalance() {
        // Structural integrity — count of begin == count of end.
        String body = BslRunnerTemplates.clientModuleBody();
        int procStart = count(body, "Процедура ");
        int procEnd = count(body, "КонецПроцедуры");
        int funcStart = count(body, "Функция ");
        int funcEnd = count(body, "КонецФункции");
        assertEquals("Процедура count must equal КонецПроцедуры count", procStart, procEnd);
        assertEquals("Функция count must equal КонецФункции count", funcStart, funcEnd);
        int regionStart = count(body, "#Область");
        int regionEnd = count(body, "#КонецОбласти");
        assertEquals("#Область must equal #КонецОбласти", regionStart, regionEnd);
    }

    @Test public void serverModuleBody_executeFunction_usesВыполнитьAndPopytka() {
        String body = BslRunnerTemplates.serverModuleBody();
        assertEquals("ВыполнитьТесты should appear exactly once",
            1, count(body, "Функция ВыполнитьТесты(Селектор) Экспорт"));
        // Выполнить — операторная форма для вызова процедуры; Вычислить — функция,
        // её нельзя использовать как statement. Live smoke 2026-05-17 поймал баг
        // компиляции BSL: «Встроенная функция может быть использована только в выражении».
        assertTrue("must use Выполнить (statement form, not Вычислить)",
            body.contains("Выполнить("));
        assertFalse("must NOT use Вычислить (cannot be used as a statement in BSL)",
            body.contains("Вычислить("));
        assertTrue("must use Попытка", body.contains("Попытка"));
        assertTrue("must handle Исключение", body.contains("Исключение"));
        assertTrue("must call ПодробноеПредставлениеОшибки",
            body.contains("ПодробноеПредставлениеОшибки"));
        // И — зарезервированное слово в BSL; имя переменной цикла не должно его использовать.
        assertFalse("loop variable must not be reserved word И",
            body.contains("Для Каждого И Из"));
    }

    @Test public void serverModuleBody_helperFunctions_areDefined() {
        String body = BslRunnerTemplates.serverModuleBody();
        assertEquals("СобратьТесты must be defined exactly once",
            1, count(body, "Функция СобратьТесты("));
        assertEquals("ПодсчётСтатусов must be defined exactly once",
            1, count(body, "Функция ПодсчётСтатусов("));
    }

    @Test public void serverModuleBody_procedureFunctionBalance() {
        String body = BslRunnerTemplates.serverModuleBody();
        int procStart = count(body, "Процедура ");
        int procEnd = count(body, "КонецПроцедуры");
        int funcStart = count(body, "Функция ");
        int funcEnd = count(body, "КонецФункции");
        assertEquals("Процедура count must equal КонецПроцедуры count", procStart, procEnd);
        assertEquals("Функция count must equal КонецФункции count", funcStart, funcEnd);
        int regionStart = count(body, "#Область");
        int regionEnd = count(body, "#КонецОбласти");
        assertEquals("#Область must equal #КонецОбласти", regionStart, regionEnd);
    }

    @Test public void serverModuleBody_doesNotReferenceUndefinedSelectorTests() {
        // Defensive: the Selector struct sent from client has Mode/ResultFile/ModuleFqn/MethodName,
        // not Tests. Server must iterate СобратьТесты(Селектор) result, not a non-existent field.
        String body = BslRunnerTemplates.serverModuleBody();
        assertTrue("must not reference undefined Селектор.Tests",
            !body.contains("Селектор.Tests"));
    }

    @Test public void serverModuleBody_doesNotHaveCyrillicTypos() {
        // Regression: a previous version had "Пassed" (П is Cyrillic, would break BSL).
        // Variable identifiers must be entirely Latin or entirely Cyrillic, not mixed.
        String body = BslRunnerTemplates.serverModuleBody();
        // The Cyrillic-П-followed-by-Latin pattern is the specific bug:
        assertTrue("must not contain Cyrillic-П with Latin assed/etc.",
            !body.contains("Пa") && !body.contains("Пs"));
    }

    @Test public void handlerProcedureForConfiguration_isNamedПриНачалеРаботыСистемы() {
        String text = BslRunnerTemplates.handlerProcedureForConfiguration();
        assertTrue("must declare ПриНачалеРаботыСистемы",
            text.contains("Процедура ПриНачалеРаботыСистемы()"));
        assertTrue("must delegate to client module",
            text.contains("EDT_MCP_TestRunner_Клиент.ВыполнитьЕсли7bПараметр()"));
        assertTrue("must NOT have &После annotation", !text.contains("&После"));
    }

    @Test public void handlerProcedureForExtension_hasАфтерAnnotation() {
        String text = BslRunnerTemplates.handlerProcedureForExtension();
        assertTrue("must have &После annotation",
            text.contains("&После(\"ПриНачалеРаботыСистемы\")"));
        assertTrue("must declare a unique procedure name",
            text.contains("Процедура EDT_MCP_ПриНачалеРаботыСистемы()"));
        assertTrue("must delegate to client module",
            text.contains("EDT_MCP_TestRunner_Клиент.ВыполнитьЕсли7bПараметр()"));
    }

    private static int count(String haystack, String needle) {
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) != -1) { c++; i += needle.length(); }
        return c;
    }
}
