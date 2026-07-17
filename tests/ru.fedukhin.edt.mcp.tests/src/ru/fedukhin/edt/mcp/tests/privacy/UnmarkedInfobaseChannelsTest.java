package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.AuditLog;
import ru.fedukhin.edt.mcp.core.privacy.InfobaseFlagStore;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalog;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyRedactor;
import ru.fedukhin.edt.mcp.core.privacy.Pseudonymizer;
import ru.fedukhin.edt.mcp.tools.debug.SetVariableTool;
import ru.fedukhin.edt.mcp.tools.testrun.RunTestMethodTool;
import ru.fedukhin.edt.mcp.tools.testrun.RunTestsTool;

/**
 * Каналы, реально возвращающие данные ИБ, но не помеченные returnsInfobaseData():
 * assert-сообщения xUnit формируются в 1С и содержат значения базы, поле error
 * у set_variable приходит от debug-движка.
 */
public class UnmarkedInfobaseChannelsTest {

    @Test public void testRunAndSetVariableDeclareCapability() {
        assertTrue("run_tests не помечен returnsInfobaseData",
            new RunTestsTool(null, null, null).returnsInfobaseData());
        assertTrue("run_test_method не помечен returnsInfobaseData",
            new RunTestMethodTool(null, null, null).returnsInfobaseData());
        assertTrue("set_variable не помечен returnsInfobaseData",
            new SetVariableTool(null, null).returnsInfobaseData());
    }

    /** Форма результата run_tests идёт в default-ветку deepRegex — проверяем, что она её накрывает. */
    @SuppressWarnings("unchecked")
    @Test public void runTestsAssertMessageIsMaskedByDeepRegex() {
        Map<String, Object> test = new LinkedHashMap<>();
        test.put("name", "ТестСоздания");
        test.put("status", "failed");
        test.put("message", "Ожидали 'Иванов, тел. +7 912 345-67-89', получили ''");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", 0);
        result.put("failed", 1);
        result.put("tests", new ArrayList<>(List.of(test)));

        PrivacyRedactor redactor = new PrivacyRedactor(
            () -> PiiCatalog.builder().build(),
            new Pseudonymizer("k".getBytes(StandardCharsets.UTF_8)),
            new InfobaseFlagStore(new HashMap<>()),
            new AuditLog());
        Object out = redactor.redact("run_tests", null, result);

        String msg = (String) ((List<Map<String, Object>>) ((Map<String, Object>) out).get("tests"))
            .get(0).get("message");
        assertFalse("телефон утёк через assert-сообщение xUnit: " + msg,
                    msg.contains("912 345-67-89"));
    }
}
