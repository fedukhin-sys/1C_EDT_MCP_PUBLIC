package ru.fedukhin.edt.mcp.tests.tools.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.tests.internal.BslTestMethodAppender;
import ru.fedukhin.edt.mcp.tools.tests.internal.BslTestMethodAppender.AppendResult;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates.Language;

public class BslTestMethodAppenderTest {

    private final BslTestMethodAppender appender = new BslTestMethodAppender();

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
}
