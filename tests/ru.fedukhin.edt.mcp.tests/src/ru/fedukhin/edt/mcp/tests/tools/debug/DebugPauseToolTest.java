package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.DebugPauseTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateDto;

public class DebugPauseToolTest {

    private final DebugSessionRegistry registry = mock(DebugSessionRegistry.class);
    private final DebugPauseTool tool = new DebugPauseTool(registry);

    @Test
    public void metadata_isCorrect() {
        assertEquals("debug_pause", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_happy_pausesAndReturnsState() throws Exception {
        UUID id = UUID.randomUUID();
        DebugSession session = mock(DebugSession.class);
        when(session.pause()).thenReturn(
                new DebugStateDto(id.toString(), DebugStateDto.SUSPENDED, false, null, null));
        when(registry.require(id)).thenReturn(session);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args);

        verify(session).pause();
        assertEquals("suspended", out.get("state"));
        assertEquals(id.toString(), out.get("debugSessionId"));
    }

    @Test
    public void call_unknownSession_throwsToolException() throws Exception {
        UUID id = UUID.randomUUID();
        when(registry.require(id)).thenThrow(
                new ToolException("debug session '" + id + "' not found"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }
}
