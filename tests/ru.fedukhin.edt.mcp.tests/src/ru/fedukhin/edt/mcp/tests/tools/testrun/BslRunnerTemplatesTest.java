package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.testrun.internal.BslRunnerTemplates;

public class BslRunnerTemplatesTest {

    @Test public void clientModuleBody_entryProcedure_callsServerAndExits() {
        String body = BslRunnerTemplates.clientModuleBody("EDT_MCP_TestRunner_Сервер_Demo");
        assertEquals("ВыполнитьЕсли7bПараметр should appear exactly once",
            1, count(body, "Процедура ВыполнитьЕсли7bПараметр()"));
        assertTrue("must reference ПараметрЗапуска", body.contains("ПараметрЗапуска"));
        assertTrue("must reference our protocol prefix", body.contains("EDT_MCP_TESTS="));
        assertTrue("must call server runner",
            body.contains("EDT_MCP_TestRunner_Сервер_Demo.ВыполнитьТесты"));
        assertTrue("must call ЗавершитьРаботуСистемы(Ложь)",
            body.contains("ЗавершитьРаботуСистемы(Ложь)"));
    }

    @Test public void clientModuleBody_helperProcedures_areDefined() {
        // Entry procedure CALLS these — they must be defined in the same module.
        String body = BslRunnerTemplates.clientModuleBody("EDT_MCP_TestRunner_Сервер_Demo");
        assertEquals("РазобратьСелектор must be defined exactly once",
            1, count(body, "Функция РазобратьСелектор("));
        assertEquals("РаскодироватьБазу64 must be defined exactly once",
            1, count(body, "Функция РаскодироватьБазу64("));
        assertEquals("ЗаписатьРезультат must be defined exactly once",
            1, count(body, "Процедура ЗаписатьРезультат("));
    }

    /**
     * 12.10.2026: раннеры стояли в двух расширениях одной базы, обработчик старта есть
     * у каждого, и один вызов run_test_method прогонял тест дважды. Селектор несёт имя серверного
     * модуля целевого раннера (ключ run), чужой раннер обязан выйти до запуска тестов.
     */
    @Test public void clientModuleBody_exitsWhenSelectorIsAddressedToAnotherRunner() {
        String body = BslRunnerTemplates.clientModuleBody("EDT_MCP_TestRunner_Сервер_Demo");
        assertTrue("ключ run должен разбираться в Runner",
            body.contains("ИначеЕсли Ключ = \"run\" Тогда Результат.Runner = Значение;"));
        String guard = "Если ЗначениеЗаполнено(Селектор.Runner) "
            + "И Селектор.Runner <> \"EDT_MCP_TestRunner_Сервер_Demo\" Тогда";
        int guardAt = body.indexOf(guard);
        assertTrue("нужна проверка адресата селектора: " + body, guardAt >= 0);
        assertTrue("после проверки адресата — выход", body.indexOf("Возврат;", guardAt) > guardAt);
        assertTrue("проверка адресата — до запуска серверного раннера",
            guardAt < body.indexOf("EDT_MCP_TestRunner_Сервер_Demo.ВыполнитьТесты"));
        assertTrue("проверка адресата — до клиентского теста",
            guardAt < body.indexOf("Результат = ВыполнитьКлиентскийТест(Селектор)"));
        assertTrue("структура селектора должна иметь поле Runner (пустое для старого плагина)",
            body.contains("\"Mode, ResultFile, ModuleFqn, MethodName, Runner, TestList\""));
    }

    /**
     * run_tests по модулю и по всем модулям молча отдавал 0 тестов: СобратьТесты знал только MTD.
     * Список «Модуль.Метод» собирает Java и передаёт файлом (ключ lst); читает его клиент — у
     * серверного процесса 1С может не быть доступа к %TEMP% пользователя.
     */
    @Test public void clientModuleBody_readsTestListFileOnClient() {
        String body = BslRunnerTemplates.clientModuleBody("EDT_MCP_TestRunner_Сервер_Demo");
        assertTrue(body.contains(
            "ИначеЕсли Ключ = \"lst\" Тогда Результат.TestList = ПрочитатьСписокТестов(РаскодироватьБазу64(Значение));"));
        assertEquals(1, count(body, "Функция ПрочитатьСписокТестов(Путь)"));
        assertTrue("файл списка — UTF-8", body.contains("Новый ЧтениеТекста(Путь, КодировкаТекста.UTF8)"));
        assertFalse("переменная «Строка» затенила бы глобальную функцию Строка()",
            body.contains("\tСтрока = "));
    }

    @Test public void serverModuleBody_collectsModuleAndAllFromTestList() {
        String body = BslRunnerTemplates.serverModuleBody();
        assertTrue("список берётся безопасно — раннер мог получить селектор без него",
            body.contains("Селектор.Свойство(\"TestList\", Список)"));
        assertTrue("«Модуль.Метод» делится по последней точке",
            body.contains("СтрНайти(Полное, \".\", НаправлениеПоиска.СКонца)"));
        assertFalse("заглушка «будут перечислять в будущей итерации» должна уйти",
            body.contains("будущей итерации"));
    }

    /**
     * Протокол клиентских тестов (модули «…Клиент» и асинхронное завершение) жил только в базах,
     * куда его дописали руками; повторный install_test_runner обновляет модули по шаблону и стёр
     * бы его — поэтому он в шаблоне.
     */
    @Test public void clientModuleBody_runsClientModulesOnClientWithAsyncCompletion() {
        String body = BslRunnerTemplates.clientModuleBody("EDT_MCP_TestRunner_Сервер_Demo");
        assertTrue("модули «…Клиент» в режиме одного метода выполняются на клиенте",
            body.contains("Если Селектор.Mode = \"MTD\" И СтрЗаканчиваетсяНа(Селектор.ModuleFqn, \"Клиент\") Тогда"));
        assertEquals(1, count(body, "Функция ВыполнитьКлиентскийТест(Селектор)"));
        assertTrue("асинхронный тест объявляет себя исключением EDT_MCP_ASYNC",
            body.contains("\"EDT_MCP_ASYNC\""));
        assertEquals("завершение асинхронного теста вызывается из теста — процедура экспортная",
            1, count(body, "Процедура ЗавершитьКлиентскийТест(ТекстОшибки = \"\", НачалоМс = 0) Экспорт"));
    }

    @Test public void clientModuleBody_procedureFunctionBalance() {
        // Structural integrity — count of begin == count of end.
        String body = BslRunnerTemplates.clientModuleBody("EDT_MCP_TestRunner_Сервер_Demo");
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
        String text = BslRunnerTemplates.handlerProcedureForConfiguration("EDT_MCP_TestRunner_Клиент_Demo");
        assertTrue("must declare ПриНачалеРаботыСистемы",
            text.contains("Процедура ПриНачалеРаботыСистемы()"));
        assertTrue("must delegate to client module",
            text.contains("EDT_MCP_TestRunner_Клиент_Demo.ВыполнитьЕсли7bПараметр()"));
        assertTrue("must NOT have &После annotation", !text.contains("&После"));
    }

    @Test public void handlerProcedureForExtension_hasАфтерAnnotation() {
        String text = BslRunnerTemplates.handlerProcedureForExtension("EDT_MCP_TestRunner_Клиент_Demo");
        assertTrue("must have &После annotation",
            text.contains("&После(\"ПриНачалеРаботыСистемы\")"));
        assertTrue("must declare a unique procedure name",
            text.contains("Процедура EDT_MCP_ПриНачалеРаботыСистемы()"));
        assertTrue("must delegate to client module",
            text.contains("EDT_MCP_TestRunner_Клиент_Demo.ВыполнитьЕсли7bПараметр()"));
    }

    private static int count(String haystack, String needle) {
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) != -1) { c++; i += needle.length(); }
        return c;
    }
}
