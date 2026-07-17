package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
 * Текст исключения инструмента, возвращающего данные ИБ, тоже уходит клиенту —
 * BM/EMF/debug-движок вкладывают в message фрагменты значений базы.
 */
public class ToolErrorRedactionTest {

    private static final String PHONE = "+7 912 345-67-89";

    private static class ThrowingTool implements IMcpTool {
        private final boolean sensitive;
        private final RuntimeException runtime;
        private final ToolException checked;

        ThrowingTool(boolean sensitive, RuntimeException runtime, ToolException checked) {
            this.sensitive = sensitive;
            this.runtime = runtime;
            this.checked = checked;
        }

        @Override public String name() { return "get_variables"; }
        @Override public String description() { return "d"; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public Object call(Map<String, Object> args) throws ToolException {
            if (checked != null) throw checked;
            throw runtime;
        }
        @Override public boolean returnsInfobaseData() { return sensitive; }
    }

    private static String callAndGetText(IMcpTool tool) {
        PrivacyRedactor redactor = new PrivacyRedactor(
            () -> PiiCatalog.builder().build(),
            new Pseudonymizer("k".getBytes(StandardCharsets.UTF_8)),
            new InfobaseFlagStore(new HashMap<>()),
            new AuditLog());
        ToolSpecAdapter adapter = new ToolSpecAdapter(redactor);
        SyncToolSpecification spec = adapter.adapt(tool);
        McpSchema.CallToolResult res = spec.callHandler()
            .apply(null, new McpSchema.CallToolRequest(tool.name(), Map.of()));
        assertTrue(Boolean.TRUE.equals(res.isError()));
        return ((McpSchema.TextContent) res.content().get(0)).text();
    }

    @Test public void runtimeExceptionMessageIsMasked() {
        String text = callAndGetText(new ThrowingTool(
            true, new RuntimeException("сбой у абонента " + PHONE), null));
        assertFalse("телефон утёк через текст RuntimeException: " + text, text.contains(PHONE));
    }

    @Test public void toolExceptionMessageIsMasked() {
        String text = callAndGetText(new ThrowingTool(
            true, null, new ToolException("не найден контакт " + PHONE)));
        assertFalse("телефон утёк через текст ToolException: " + text, text.contains(PHONE));
    }

    /** Инструменты без данных ИБ не трогаем — текст ошибки должен остаться диагностируемым. */
    @Test public void nonSensitiveToolErrorIsNotMasked() {
        String text = callAndGetText(new ThrowingTool(
            false, null, new ToolException("не найден контакт " + PHONE)));
        assertTrue(text.contains(PHONE));
    }
}
