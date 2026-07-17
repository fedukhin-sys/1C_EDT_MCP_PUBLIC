package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.internal.protocol.ToolSpecAdapter;
import ru.fedukhin.edt.mcp.core.privacy.AuditLog;
import ru.fedukhin.edt.mcp.core.privacy.InfobaseFlagStore;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalog;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyRedactor;
import ru.fedukhin.edt.mcp.core.privacy.Pseudonymizer;

/**
 * Ключ инфобазы для проверки флага containsRealPersonalData берётся у самого инструмента,
 * а не из сырых args: иначе клиент дописывает в аргументы debug-инструмента лишний ключ
 * "name" с именем «безопасной» базы и отключает обезличивание чужих данных.
 */
public class PrivacyInfobaseKeySpoofingTest {

    private static PrivacyRedactor redactor(InfobaseFlagStore flags) {
        return new PrivacyRedactor(
            () -> PiiCatalog.builder().build(),
            new Pseudonymizer("k".getBytes(StandardCharsets.UTF_8)),
            flags,
            new AuditLog());
    }

    /** Инструмент без аргументов инфобазы (get_variables/evaluate/get_stack). */
    private static class DebugTool implements IMcpTool {
        @Override public String name() { return "get_variables"; }
        @Override public String description() { return "d"; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public Object call(Map<String, Object> args) throws ToolException { return null; }
        @Override public boolean returnsInfobaseData() { return true; }
    }

    /** Инструмент, у которого ключ инфобазы честный (query_event_log). */
    private static class EventLogTool implements IMcpTool {
        @Override public String name() { return "query_event_log"; }
        @Override public String description() { return "d"; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public Object call(Map<String, Object> args) throws ToolException { return null; }
        @Override public boolean returnsInfobaseData() { return true; }
        @Override public String privacyInfobaseKey(Map<String, Object> args) {
            return args.get("name") instanceof String s && !s.isBlank() ? s : null;
        }
    }

    private static List<Map<String, Object>> varsWithSnils() {
        List<Map<String, Object>> vars = new ArrayList<>();
        vars.add(new LinkedHashMap<>(Map.of("name", "СНИЛС", "type", "Строка", "value", "112-233-445 95")));
        return vars;
    }

    @SuppressWarnings("unchecked")
    @Test public void spoofedNameArgDoesNotDisableRedaction() {
        InfobaseFlagStore flags = new InfobaseFlagStore(new HashMap<>());
        flags.setFlag("safe-base", false);
        ToolSpecAdapter adapter = new ToolSpecAdapter(redactor(flags));

        Object out = adapter.applyPrivacy(new DebugTool(), Map.of("name", "safe-base"), varsWithSnils());

        String v = (String) ((List<Map<String, Object>>) out).get(0).get("value");
        assertTrue("подложный args[name] отключил обезличивание debug-инструмента: " + v,
                   v.startsWith("Физлицо#"));
    }

    @SuppressWarnings("unchecked")
    @Test public void honestInfobaseKeyStillHonoursFlag() {
        InfobaseFlagStore flags = new InfobaseFlagStore(new HashMap<>());
        flags.setFlag("TestBase", false);
        ToolSpecAdapter adapter = new ToolSpecAdapter(redactor(flags));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("user", "ИвановИИ");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", new ArrayList<>(List.of(ev)));

        Object out = adapter.applyPrivacy(new EventLogTool(), Map.of("name", "TestBase"), result);

        assertEquals("ИвановИИ",
            ((List<Map<String, Object>>) ((Map<String, Object>) out).get("events")).get(0).get("user"));
    }
}
