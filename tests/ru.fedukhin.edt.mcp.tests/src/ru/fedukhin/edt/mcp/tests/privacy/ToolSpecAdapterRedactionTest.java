package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;

import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.internal.protocol.ToolSpecAdapter;
import ru.fedukhin.edt.mcp.core.privacy.IPrivacyFilter;

/** Проверяет, что ToolSpecAdapter пропускает результат через IPrivacyFilter только когда инструмент помечен returnsInfobaseData(). */
public class ToolSpecAdapterRedactionTest {

    private static class RecordingFilter implements IPrivacyFilter {
        boolean called = false;

        @Override
        public Object redact(String toolName, Map<String, Object> args, Object result) {
            called = true;
            return "REDACTED";
        }
    }

    private static class FakeTool implements IMcpTool {
        private final boolean sensitive;

        FakeTool(boolean sensitive) {
            this.sensitive = sensitive;
        }

        @Override public String name() { return "x"; }
        @Override public String description() { return "d"; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public Object call(Map<String, Object> args) throws ToolException { return "RAW"; }
        @Override public boolean returnsInfobaseData() { return sensitive; }
    }

    @Test
    public void sensitiveToolResultIsRedacted() {
        RecordingFilter recording = new RecordingFilter();
        ToolSpecAdapter adapter = new ToolSpecAdapter(recording);

        Object result = adapter.applyPrivacy(new FakeTool(true), Map.of(), "RAW");

        assertEquals("REDACTED", result);
        assertTrue(recording.called);
    }

    @Test
    public void nonSensitiveToolResultPassesThrough() {
        RecordingFilter recording = new RecordingFilter();
        ToolSpecAdapter adapter = new ToolSpecAdapter(recording);

        Object result = adapter.applyPrivacy(new FakeTool(false), Map.of(), "RAW");

        assertEquals("RAW", result);
        assertFalse(recording.called);
    }
}
