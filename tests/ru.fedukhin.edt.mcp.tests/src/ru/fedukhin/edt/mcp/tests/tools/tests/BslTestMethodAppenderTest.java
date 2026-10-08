package ru.fedukhin.edt.mcp.tests.tools.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.tests.internal.BslTestMethodAppender;
import ru.fedukhin.edt.mcp.tools.tests.internal.BslTestMethodAppender.AppendResult;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates.Language;

public class BslTestMethodAppenderTest {

    private final BslTestMethodAppender appender = new BslTestMethodAppender();
    private static final String BOM = String.valueOf((char) 0xFEFF);

    private static final String RU_MODULE = ""
        + "#Область ПрограммныйИнтерфейс\r\n\r\n"
        + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
        + "КонецПроцедуры\r\n\r\n"
        + "#КонецОбласти\r\n";

    private static final String EN_MODULE = ""
        + "#Region Public\r\n\r\n"
        + "Procedure ExecutableScenarios(UnitTests) Export\r\n"
        + "EndProcedure\r\n\r\n"
        + "#EndRegion\r\n";

    // Test 1: append new method to RU module → new text contains ДобавитьТест + Процедура Тест_X
    @Test
    public void appendNewMethod_ruModule_containsRegistrationAndProcedure() {
        AppendResult result = appender.append(RU_MODULE, "СозданиеСправочника", Language.RU, null);
        assertFalse(result.alreadyExisted);
        assertTrue("should contain ДобавитьТест registration",
                result.newText.contains("ЮнитТесты.ДобавитьТест(\"Тест_СозданиеСправочника\")"));
        assertTrue("should contain Процедура Тест_",
                result.newText.contains("Процедура Тест_СозданиеСправочника()"));
        assertTrue("should contain КонецПроцедуры",
                result.newText.contains("КонецПроцедуры"));
        // Regression: String.replace("",X) bug caused the registration line to be
        // inserted between every character of "Процедура ИсполняемыеСценарии…" when
        // the body was empty. Make sure the procedure header appears exactly once
        // and the registration line appears exactly once.
        assertEquals("ИсполняемыеСценарии header must appear exactly once",
                1, countOccurrences(result.newText, "Процедура ИсполняемыеСценарии"));
        assertEquals("ДобавитьТест(\"Тест_СозданиеСправочника\") must appear exactly once",
                1, countOccurrences(result.newText,
                    "ЮнитТесты.ДобавитьТест(\"Тест_СозданиеСправочника\")"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { count++; idx += needle.length(); }
        return count;
    }

    // Test 2: append duplicate → alreadyExisted=true, text unchanged
    @Test
    public void appendDuplicate_returnsAlreadyExisted() {
        String moduleWithMethod = RU_MODULE
                + "\r\nПроцедура Тест_МойТест() Экспорт\r\n\t// TODO\r\nКонецПроцедуры\r\n";
        AppendResult result = appender.append(moduleWithMethod, "МойТест", Language.RU, null);
        assertTrue(result.alreadyExisted);
        assertEquals(moduleWithMethod, result.newText);
    }

    // Test 3: append to EN module → AddTest + Procedure Test_X
    @Test
    public void appendNewMethod_enModule_containsRegistrationAndProcedure() {
        AppendResult result = appender.append(EN_MODULE, "CreateCatalog", Language.EN, null);
        assertFalse(result.alreadyExisted);
        assertTrue("should contain AddTest registration",
                result.newText.contains("UnitTests.AddTest(\"Test_CreateCatalog\")"));
        assertTrue("should contain Procedure Test_",
                result.newText.contains("Procedure Test_CreateCatalog()"));
        assertTrue("should contain EndProcedure",
                result.newText.contains("EndProcedure"));
    }

    // Test 4: module without ExecutableScenarios → method appended at end without ДобавитьТест
    @Test
    public void appendToModuleWithoutExecutableScenarios_methodAddedAtEnd() {
        String plain = "// Simple module\r\n";
        AppendResult result = appender.append(plain, "MyTest", Language.EN, null);
        assertFalse(result.alreadyExisted);
        assertTrue("method should be at end", result.newText.contains("Procedure Test_MyTest()"));
        assertFalse("no AddTest because no ExecutableScenarios",
                result.newText.contains("AddTest"));
    }

    // Test 5: user body embedded if provided
    @Test
    public void appendWithUserBody_bodyIsEmbedded() {
        String userBody = "\tAssertEquals(42, answer);\r\n";
        AppendResult result = appender.append(RU_MODULE, "Ответ", Language.RU, userBody);
        assertFalse(result.alreadyExisted);
        assertTrue("custom body should appear", result.newText.contains("AssertEquals(42, answer)"));
        assertFalse("default TODO should not appear", result.newText.contains("TODO: написать тест"));
    }

    // ---- Регрессии 2026-10-08 (CommonModule.ДемоТест_Закупки) ----
    //
    // Наблюдалось: метод дописан после последнего #КонецОбласти, последняя строка тела
    // склеилась с КонецПроцедуры, префикс Тест_ удвоен (Тест_Тест_…), из-за чего
    // run_test_method по fqn из ответа падал «Метод объекта не обнаружен».

    private static final String TWO_REGIONS_MODULE = ""
        + "#Область ПрограммныйИнтерфейс\r\n\r\n"
        + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
        + "КонецПроцедуры\r\n\r\n"
        + "Процедура Тест_Существующий() Экспорт\r\n"
        + "КонецПроцедуры\r\n\r\n"
        + "#КонецОбласти\r\n\r\n"
        + "#Область СлужебныеПроцедурыИФункции\r\n\r\n"
        + "Процедура Лог(Текст)\r\n"
        + "КонецПроцедуры\r\n\r\n"
        + "#КонецОбласти\r\n";

    @Test
    public void prefixedMethodName_isNotPrefixedTwice() {
        AppendResult result = appender.append(RU_MODULE, "Тест_Закупки_Диагностика2", Language.RU, null);
        assertEquals("Тест_Закупки_Диагностика2", result.fqn);
        assertTrue(result.newText.contains("Процедура Тест_Закупки_Диагностика2() Экспорт"));
        assertTrue(result.newText.contains("ЮнитТесты.ДобавитьТест(\"Тест_Закупки_Диагностика2\")"));
        assertFalse("префикс Тест_ не должен удваиваться", result.newText.contains("Тест_Тест_"));
    }

    @Test
    public void prefixedMethodName_enModule_isNotPrefixedTwice() {
        AppendResult result = appender.append(EN_MODULE, "Test_CreateCatalog", Language.EN, null);
        assertEquals("Test_CreateCatalog", result.fqn);
        assertTrue(result.newText.contains("Procedure Test_CreateCatalog() Export"));
        assertFalse(result.newText.contains("Test_Test_"));
    }

    @Test
    public void prefixedMethodName_prefixCaseIsIgnored_spellingKept() {
        // Идентификаторы BSL регистронезависимы: «тест_X» — уже тестовый метод.
        AppendResult result = appender.append(RU_MODULE, "тест_Регистр", Language.RU, null);
        assertEquals("тест_Регистр", result.fqn);
        assertFalse(result.newText.contains("Тест_тест_"));
    }

    @Test
    public void unprefixedMethodName_fqnGetsPrefix() {
        AppendResult result = appender.append(RU_MODULE, "СозданиеСправочника", Language.RU, null);
        assertEquals("Тест_СозданиеСправочника", result.fqn);
    }

    @Test
    public void duplicateWithPrefixedName_returnsAlreadyExisted() {
        String moduleWithMethod = RU_MODULE
                + "\r\nПроцедура Тест_МойТест() Экспорт\r\n\t// TODO\r\nКонецПроцедуры\r\n";
        AppendResult result = appender.append(moduleWithMethod, "Тест_МойТест", Language.RU, null);
        assertTrue(result.alreadyExisted);
        assertEquals("Тест_МойТест", result.fqn);
        assertEquals(moduleWithMethod, result.newText);
    }

    @Test
    public void bodyWithoutTrailingNewline_endProcedureStartsOwnLine() {
        String body = "\tЛог(\"a\");\r\n\tЛог(\"b\");";
        AppendResult result = appender.append(RU_MODULE, "Склейка", Language.RU, body);
        assertFalse("КонецПроцедуры приклеился к последнему оператору",
                result.newText.contains("Лог(\"b\");КонецПроцедуры"));
        assertTrue(result.newText.contains("\tЛог(\"b\");\r\nКонецПроцедуры\r\n"));
    }

    @Test
    public void newMethod_isInsertedInsidePublicRegion_exactText() {
        AppendResult result = appender.append(RU_MODULE, "Новый", Language.RU, null);
        String expected = ""
            + "#Область ПрограммныйИнтерфейс\r\n\r\n"
            + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
            + "\tЮнитТесты.ДобавитьТест(\"Тест_Новый\");\r\n"
            + "КонецПроцедуры\r\n\r\n"
            + "Процедура Тест_Новый() Экспорт\r\n"
            + "\t// TODO: написать тест\r\n"
            + "КонецПроцедуры\r\n\r\n"
            + "#КонецОбласти\r\n";
        assertEquals(expected, result.newText);
    }

    @Test
    public void newMethod_enModule_isInsertedInsidePublicRegion_exactText() {
        AppendResult result = appender.append(EN_MODULE, "New", Language.EN, null);
        String expected = ""
            + "#Region Public\r\n\r\n"
            + "Procedure ExecutableScenarios(UnitTests) Export\r\n"
            + "\tUnitTests.AddTest(\"Test_New\");\r\n"
            + "EndProcedure\r\n\r\n"
            + "Procedure Test_New() Export\r\n"
            + "\t// TODO: write test\r\n"
            + "EndProcedure\r\n\r\n"
            + "#EndRegion\r\n";
        assertEquals(expected, result.newText);
    }

    @Test
    public void twoRegions_methodGoesBeforeEndOfPublicRegion_serviceRegionUntouched() {
        AppendResult result = appender.append(TWO_REGIONS_MODULE, "Новый", Language.RU, null);
        String text = result.newText;
        int method = text.indexOf("Процедура Тест_Новый() Экспорт");
        int firstEnd = text.indexOf("#КонецОбласти");
        int serviceRegion = text.indexOf("#Область СлужебныеПроцедурыИФункции");
        assertTrue(method > 0);
        assertTrue("метод должен быть до первого #КонецОбласти", method < firstEnd);
        assertTrue(firstEnd < serviceRegion);
        assertTrue("служебная область и хвост файла не тронуты",
                text.endsWith("Процедура Лог(Текст)\r\nКонецПроцедуры\r\n\r\n#КонецОбласти\r\n"));
    }

    @Test
    public void endRegionWithoutBlankLineBefore_blankLinesAddedAroundMethod() {
        String module = ""
            + "#Область ПрограммныйИнтерфейс\r\n"
            + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
            + "КонецПроцедуры\r\n"
            + "#КонецОбласти\r\n";
        AppendResult result = appender.append(module, "Новый", Language.RU, null);
        assertEquals(""
            + "#Область ПрограммныйИнтерфейс\r\n"
            + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
            + "\tЮнитТесты.ДобавитьТест(\"Тест_Новый\");\r\n"
            + "КонецПроцедуры\r\n\r\n"
            + "Процедура Тест_Новый() Экспорт\r\n"
            + "\t// TODO: написать тест\r\n"
            + "КонецПроцедуры\r\n\r\n"
            + "#КонецОбласти\r\n", result.newText);
    }

    @Test
    public void publicRegionMissing_methodGoesBeforeFirstEndRegion() {
        String module = ""
            + "#Область Тесты\r\n\r\n"
            + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
            + "КонецПроцедуры\r\n\r\n"
            + "#КонецОбласти\r\n\r\n"
            + "#Область СлужебныеПроцедурыИФункции\r\n"
            + "#КонецОбласти\r\n";
        AppendResult result = appender.append(module, "Новый", Language.RU, null);
        String text = result.newText;
        assertTrue(text.indexOf("Процедура Тест_Новый() Экспорт") < text.indexOf("#КонецОбласти"));
        assertTrue(text.endsWith("#Область СлужебныеПроцедурыИФункции\r\n#КонецОбласти\r\n"));
    }

    @Test
    public void nestedRegionInsidePublic_methodGoesBeforeOuterEndRegion() {
        String module = ""
            + "#Область ПрограммныйИнтерфейс\r\n\r\n"
            + "#Область Вложенная\r\n"
            + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
            + "КонецПроцедуры\r\n"
            + "#КонецОбласти\r\n\r\n"
            + "#КонецОбласти\r\n";
        AppendResult result = appender.append(module, "Новый", Language.RU, null);
        String text = result.newText;
        int method = text.indexOf("Процедура Тест_Новый() Экспорт");
        int innerEnd = text.indexOf("#КонецОбласти");
        int outerEnd = text.lastIndexOf("#КонецОбласти");
        assertTrue("метод должен быть после закрытия вложенной области", method > innerEnd);
        assertTrue("…но до закрытия ПрограммныйИнтерфейс", method < outerEnd);
    }

    @Test
    public void regionInsideProcedureBody_isNotAnInsertionPoint() {
        String module = ""
            + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
            + "\t#Область Регистрация\r\n"
            + "\t#КонецОбласти\r\n"
            + "КонецПроцедуры\r\n";
        AppendResult result = appender.append(module, "Новый", Language.RU, null);
        String text = result.newText;
        assertTrue("метод не должен попасть внутрь ИсполняемыеСценарии",
                text.indexOf("Процедура Тест_Новый() Экспорт") > text.indexOf("КонецПроцедуры"));
    }

    @Test
    public void noRegions_methodAppendedAtEndWithBlankLine() {
        String module = "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\nКонецПроцедуры\r\n";
        AppendResult result = appender.append(module, "Новый", Language.RU, null);
        assertEquals(""
            + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
            + "\tЮнитТесты.ДобавитьТест(\"Тест_Новый\");\r\n"
            + "КонецПроцедуры\r\n\r\n"
            + "Процедура Тест_Новый() Экспорт\r\n"
            + "\t// TODO: написать тест\r\n"
            + "КонецПроцедуры\r\n", result.newText);
    }

    @Test
    public void lfModule_keepsLfEverywhere() {
        String lfModule = RU_MODULE.replace("\r\n", "\n");
        AppendResult result = appender.append(lfModule, "Новый", Language.RU, "\tЛог(\"a\");\r\n");
        assertFalse("в LF-модуле не должно появиться ни одного \\r", result.newText.contains("\r"));
        assertTrue(result.newText.contains("\tЮнитТесты.ДобавитьТест(\"Тест_Новый\");\nКонецПроцедуры\n"));
        assertTrue(result.newText.contains("Процедура Тест_Новый() Экспорт\n\tЛог(\"a\");\nКонецПроцедуры\n"));
    }

    @Test
    public void crlfModule_bodyWithBareLf_isNormalizedToCrlf() {
        AppendResult result = appender.append(RU_MODULE, "Новый", Language.RU, "\tЛог(\"a\");\n\tЛог(\"b\");\n");
        assertFalse("голый \\n в CRLF-модуле", result.newText.replace("\r\n", "").contains("\n"));
        assertTrue(result.newText.contains("\tЛог(\"a\");\r\n\tЛог(\"b\");\r\nКонецПроцедуры\r\n"));
    }

    @Test
    public void bomModule_bomKeptOnceAndRegionStillFound() {
        String withBom = BOM + RU_MODULE;
        AppendResult result = appender.append(withBom, "Новый", Language.RU, null);
        String text = result.newText;
        assertTrue("BOM должен остаться в начале", text.startsWith(BOM + "#Область"));
        assertEquals("BOM ровно один", 1, countOccurrences(text, BOM));
        assertTrue("с BOM область всё равно должна найтись",
                text.indexOf("Процедура Тест_Новый() Экспорт") < text.indexOf("#КонецОбласти"));
    }
}
