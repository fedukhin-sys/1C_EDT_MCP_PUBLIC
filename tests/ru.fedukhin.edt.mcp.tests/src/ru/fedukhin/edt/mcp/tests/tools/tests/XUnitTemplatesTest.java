package ru.fedukhin.edt.mcp.tests.tools.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates.Language;

public class XUnitTemplatesTest {

    private static final String CRLF = "\r\n";

    // Test 1: module body RU contains key Russian phrases
    @Test
    public void moduleBodyRu_containsRussianKeywords() {
        String body = XUnitTemplates.moduleBody(Language.RU);
        assertTrue("expected ИсполняемыеСценарии", body.contains("ИсполняемыеСценарии"));
        assertTrue("expected ПрограммныйИнтерфейс", body.contains("ПрограммныйИнтерфейс"));
        assertTrue("expected Экспорт", body.contains("Экспорт"));
        assertTrue("expected КонецПроцедуры", body.contains("КонецПроцедуры"));
    }

    // Test 2: module body EN contains key English phrases
    @Test
    public void moduleBodyEn_containsEnglishKeywords() {
        String body = XUnitTemplates.moduleBody(Language.EN);
        assertTrue("expected ExecutableScenarios", body.contains("ExecutableScenarios"));
        assertTrue("expected Public", body.contains("Public"));
        assertTrue("expected Export", body.contains("Export"));
        assertTrue("expected EndProcedure", body.contains("EndProcedure"));
    }

    // Test 3: fqn + method body use Тест_ prefix for RU
    @Test
    public void methodBodyRu_usesTetUnderscorePrefix() {
        String fqn = XUnitTemplates.fqn("CreateCatalog", Language.RU);
        String body = XUnitTemplates.methodBody(fqn, Language.RU, null, CRLF);
        assertEquals("Тест_CreateCatalog", fqn);
        assertTrue("expected Тест_ prefix", body.contains("Тест_CreateCatalog"));
        assertTrue("expected Экспорт", body.contains("Экспорт"));
        assertTrue("expected КонецПроцедуры", body.contains("КонецПроцедуры"));
    }

    // Test 4: fqn + method body use Test_ prefix for EN
    @Test
    public void methodBodyEn_usesTestUnderscorePrefix() {
        String fqn = XUnitTemplates.fqn("CreateCatalog", Language.EN);
        String body = XUnitTemplates.methodBody(fqn, Language.EN, null, CRLF);
        assertEquals("Test_CreateCatalog", fqn);
        assertTrue("expected Test_ prefix", body.contains("Test_CreateCatalog"));
        assertTrue("expected Export", body.contains("Export"));
        assertTrue("expected EndProcedure", body.contains("EndProcedure"));
    }

    // Test 5: custom user body is embedded in method
    @Test
    public void methodBody_embedsCustomUserBody() {
        String userBody = "\tAssertEquals(1, 1);\r\n";
        String body = XUnitTemplates.methodBody("Test_MyTest", Language.EN, userBody, CRLF);
        assertTrue("custom body should be present", body.contains("AssertEquals(1, 1)"));
        assertFalse("default TODO should not appear", body.contains("TODO"));
    }

    // ---- Регрессии 2026-10-08 ----

    @Test
    public void fqn_prefixNotDuplicated_caseInsensitive() {
        assertEquals("Тест_Закупки", XUnitTemplates.fqn("Тест_Закупки", Language.RU));
        assertEquals("тест_Закупки", XUnitTemplates.fqn("тест_Закупки", Language.RU));
        assertEquals("Test_Catalog", XUnitTemplates.fqn("Test_Catalog", Language.EN));
        assertEquals("test_Catalog", XUnitTemplates.fqn("test_Catalog", Language.EN));
        assertEquals("Тест_Закупки", XUnitTemplates.fqn("Закупки", Language.RU));
    }

    @Test
    public void methodBody_exactShape_bodyWithoutTrailingNewline() {
        String body = XUnitTemplates.methodBody("Тест_Склейка", Language.RU, "\tЛог(\"a\");\r\n\tЛог(\"b\");", CRLF);
        assertEquals(""
            + "Процедура Тест_Склейка() Экспорт\r\n"
            + "\tЛог(\"a\");\r\n"
            + "\tЛог(\"b\");\r\n"
            + "КонецПроцедуры\r\n", body);
    }

    @Test
    public void methodBody_bodyLineEndingsFollowEol() {
        String body = XUnitTemplates.methodBody("Test_Lf", Language.EN, "\tA();\r\n\tB();\r\n", "\n");
        assertEquals("Procedure Test_Lf() Export\n\tA();\n\tB();\nEndProcedure\n", body);
        String todo = XUnitTemplates.methodBody("Test_Todo", Language.EN, "  ", "\n");
        assertEquals("Procedure Test_Todo() Export\n\t// TODO: write test\nEndProcedure\n", todo);
    }
}
