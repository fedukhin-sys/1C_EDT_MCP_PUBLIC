package ru.fedukhin.edt.mcp.tests.tools.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates.Language;

public class XUnitTemplatesTest {

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

    // Test 3: method body uses Тест_ prefix for RU
    @Test
    public void methodBodyRu_usesTetUnderscorePrefix() {
        String body = XUnitTemplates.methodBody("CreateCatalog", Language.RU, null);
        assertTrue("expected Тест_ prefix", body.contains("Тест_CreateCatalog"));
        assertTrue("expected Экспорт", body.contains("Экспорт"));
        assertTrue("expected КонецПроцедуры", body.contains("КонецПроцедуры"));
    }

    // Test 4: method body uses Test_ prefix for EN
    @Test
    public void methodBodyEn_usesTestUnderscorePrefix() {
        String body = XUnitTemplates.methodBody("CreateCatalog", Language.EN, null);
        assertTrue("expected Test_ prefix", body.contains("Test_CreateCatalog"));
        assertTrue("expected Export", body.contains("Export"));
        assertTrue("expected EndProcedure", body.contains("EndProcedure"));
    }

    // Test 5: custom user body is embedded in method
    @Test
    public void methodBody_embedsCustomUserBody() {
        String userBody = "\tAssertEquals(1, 1);\r\n";
        String body = XUnitTemplates.methodBody("MyTest", Language.EN, userBody);
        assertTrue("custom body should be present", body.contains("AssertEquals(1, 1)"));
        assertFalse("default TODO should not appear", body.contains("TODO"));
    }
}
