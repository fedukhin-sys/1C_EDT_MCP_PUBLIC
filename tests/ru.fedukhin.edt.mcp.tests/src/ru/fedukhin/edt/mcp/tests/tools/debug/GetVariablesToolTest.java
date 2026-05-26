package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.GetVariablesTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvaluationService;
import ru.fedukhin.edt.mcp.tools.debug.internal.VariableDto;

public class GetVariablesToolTest {

    private final DebugSessionRegistry registry = mock(DebugSessionRegistry.class);
    private final EvaluationService evaluation = mock(EvaluationService.class);
    private final GetVariablesTool tool = new GetVariablesTool(registry, evaluation);

    @Test
    public void metadata_isCorrect() {
        assertEquals("get_variables", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_suspendedFrame_returnsVariableList() throws Exception {
        UUID id = UUID.randomUUID();
        IBslStackFrame frame = mock(IBslStackFrame.class);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        DebugSession session = mock(DebugSession.class);
        when(registry.require(id)).thenReturn(session);
        when(session.findFrame("0")).thenReturn(frame);
        when(session.target()).thenReturn(target);
        when(evaluation.readVariables(eq(target), eq(frame), anyInt()))
                .thenReturn(List.of(new VariableDto("Счётчик", "Число", "7")));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("frameId", "0");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) tool.call(args);

        assertEquals(1, out.size());
        assertEquals("Счётчик", out.get(0).get("name"));
        assertEquals("Число", out.get(0).get("type"));
        assertEquals("7", out.get(0).get("value"));
    }

    @Test
    public void call_unknownSession_throwsToolException() throws Exception {
        UUID id = UUID.randomUUID();
        when(registry.require(id)).thenThrow(new ToolException("debug session '" + id + "' not found"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("frameId", "0");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    @Test
    public void call_missingFrameId_throwsToolException() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("frameId"));
        }
    }

    @Test
    public void call_unknownFrame_throwsToolException() throws Exception {
        UUID id = UUID.randomUUID();
        DebugSession session = mock(DebugSession.class);
        when(registry.require(id)).thenReturn(session);
        when(session.findFrame("9")).thenThrow(new ToolException("frame '9' not found"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("frameId", "9");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("frame"));
        }
    }
}
