package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.StopDebugTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;

public class StopDebugToolTest {

    private final DebugSessionRegistry registry = mock(DebugSessionRegistry.class);
    private final StopDebugTool tool = new StopDebugTool(registry);

    @Test
    public void metadata_isCorrect() {
        assertEquals("stop_debug", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_knownSession_removesAndTerminates() throws Exception {
        UUID id = UUID.randomUUID();
        DebugSession session = mock(DebugSession.class);
        when(registry.remove(id)).thenReturn(Optional.of(session));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args);

        verify(registry).remove(id);
        verify(session).terminate();
        assertEquals(Boolean.TRUE, out.get("stopped"));
    }

    @Test
    public void call_unknownSession_isIdempotentNoOpSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(registry.remove(id)).thenReturn(Optional.empty());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args);

        assertEquals(Boolean.TRUE, out.get("stopped"));
    }

    @Test
    public void call_invalidUuid_throwsToolException() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", "not-a-uuid");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().toLowerCase().contains("invalid"));
        }
    }
}
