package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.EvaluateTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvalOutcome;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvaluationService;

public class EvaluateToolTest {

    private final DebugSessionRegistry registry = mock(DebugSessionRegistry.class);
    private final EvaluationService evaluation = mock(EvaluationService.class);
    private final EvaluateTool tool = new EvaluateTool(registry, evaluation);

    private DebugSession sessionWithFrame(UUID id, IBslStackFrame frame,
                                          IRuntimeDebugClientTarget target) throws Exception {
        DebugSession session = mock(DebugSession.class);
        when(registry.require(id)).thenReturn(session);
        when(session.findFrame("0")).thenReturn(frame);
        when(session.target()).thenReturn(target);
        return session;
    }

    @Test
    public void metadata_isCorrect() {
        assertEquals("evaluate", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_successfulExpression_returnsOkValueType() throws Exception {
        UUID id = UUID.randomUUID();
        IBslStackFrame frame = mock(IBslStackFrame.class);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        sessionWithFrame(id, frame, target);
        when(evaluation.evaluate(eq(target), eq(frame), eq("1 + 1"), anyInt()))
                .thenReturn(EvalOutcome.success("2", "Число"));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("frameId", "0");
        args.put("expression", "1 + 1");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args);

        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("2", out.get("value"));
        assertEquals("Число", out.get("type"));
    }

    @Test
    public void call_badExpression_returnsOkFalseWithErrorNotException() throws Exception {
        UUID id = UUID.randomUUID();
        IBslStackFrame frame = mock(IBslStackFrame.class);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        sessionWithFrame(id, frame, target);
        when(evaluation.evaluate(eq(target), eq(frame), eq("не выражение"), anyInt()))
                .thenReturn(EvalOutcome.failure("syntax error"));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("frameId", "0");
        args.put("expression", "не выражение");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args);

        assertEquals(Boolean.FALSE, out.get("ok"));
        assertEquals("syntax error", out.get("error"));
        assertNull(out.get("value"));
    }

    @Test
    public void call_unknownSession_throwsToolException() throws Exception {
        UUID id = UUID.randomUUID();
        when(registry.require(id)).thenThrow(new ToolException("debug session '" + id + "' not found"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("frameId", "0");
        args.put("expression", "1");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    @Test
    public void call_missingExpression_throwsToolException() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("frameId", "0");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("expression"));
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
        args.put("expression", "1");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("frame"));
        }
    }
}
