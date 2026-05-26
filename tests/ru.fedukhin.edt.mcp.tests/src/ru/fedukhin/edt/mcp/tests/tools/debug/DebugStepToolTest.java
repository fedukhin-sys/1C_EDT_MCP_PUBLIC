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
import ru.fedukhin.edt.mcp.tools.debug.DebugStepTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession.StepKind;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateDto;

public class DebugStepToolTest {

    private final DebugSessionRegistry registry = mock(DebugSessionRegistry.class);
    private final DebugStepTool tool = new DebugStepTool(registry);

    private Map<String, Object> args(UUID id, String threadId, String kind) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("threadId", threadId);
        args.put("kind", kind);
        return args;
    }

    @Test
    public void metadata_isCorrect() {
        assertEquals("debug_step", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_over_mapsToStepKindOver() throws Exception {
        UUID id = UUID.randomUUID();
        DebugSession session = mock(DebugSession.class);
        when(session.step("0", StepKind.OVER, 30)).thenReturn(
                new DebugStateDto(id.toString(), DebugStateDto.SUSPENDED, false, null, null));
        when(registry.require(id)).thenReturn(session);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args(id, "0", "over"));

        verify(session).step("0", StepKind.OVER, 30);
        assertEquals("suspended", out.get("state"));
    }

    @Test
    public void call_into_mapsToStepKindInto() throws Exception {
        UUID id = UUID.randomUUID();
        DebugSession session = mock(DebugSession.class);
        when(session.step("1", StepKind.INTO, 30)).thenReturn(
                new DebugStateDto(id.toString(), DebugStateDto.SUSPENDED, false, null, null));
        when(registry.require(id)).thenReturn(session);

        tool.call(args(id, "1", "into"));

        verify(session).step("1", StepKind.INTO, 30);
    }

    @Test
    public void call_out_mapsToStepKindOut() throws Exception {
        UUID id = UUID.randomUUID();
        DebugSession session = mock(DebugSession.class);
        when(session.step("0", StepKind.OUT, 30)).thenReturn(
                new DebugStateDto(id.toString(), DebugStateDto.SUSPENDED, false, null, null));
        when(registry.require(id)).thenReturn(session);

        tool.call(args(id, "0", "out"));

        verify(session).step("0", StepKind.OUT, 30);
    }

    @Test
    public void call_invalidKind_throwsToolException() {
        UUID id = UUID.randomUUID();
        try {
            tool.call(args(id, "0", "sideways"));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("kind"));
        }
    }

    @Test
    public void call_explicitTimeout_isForwarded() throws Exception {
        UUID id = UUID.randomUUID();
        DebugSession session = mock(DebugSession.class);
        when(session.step("0", StepKind.OVER, 120)).thenReturn(
                new DebugStateDto(id.toString(), DebugStateDto.RUNNING, true, null, null));
        when(registry.require(id)).thenReturn(session);
        Map<String, Object> args = args(id, "0", "over");
        args.put("timeoutSeconds", 120);

        tool.call(args);

        verify(session).step("0", StepKind.OVER, 120);
    }

    @Test
    public void call_unknownSession_throwsToolException() throws Exception {
        UUID id = UUID.randomUUID();
        when(registry.require(id)).thenThrow(
                new ToolException("debug session '" + id + "' not found"));
        try {
            tool.call(args(id, "0", "over"));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }
}
