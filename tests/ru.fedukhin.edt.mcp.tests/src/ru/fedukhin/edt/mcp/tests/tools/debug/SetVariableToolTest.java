package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.SetVariableTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvalOutcome;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvaluationService;

public class SetVariableToolTest {

    private final DebugSessionRegistry registry = new DebugSessionRegistry();
    private final EvaluationService evaluation = mock(EvaluationService.class);
    private final SetVariableTool tool = new SetVariableTool(registry, evaluation);

    private DebugSession registerSession(UUID id, IBslStackFrame frame) throws ToolException {
        DebugSession session = mock(DebugSession.class);
        when(session.id()).thenReturn(id);
        when(session.target()).thenReturn(mock(IRuntimeDebugClientTarget.class));
        when(session.findFrame("0")).thenReturn(frame);
        registry.register(session);
        return session;
    }

    private static Map<String, Object> args(UUID id) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("frameId", "0");
        args.put("variableName", "Счётчик");
        args.put("valueExpression", "42");
        return args;
    }

    @Test public void metadata_isCorrect() {
        assertEquals("set_variable", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
        // set_variable отдаёт текст ошибки движка отладки — он несёт фрагменты данных базы
        assertTrue(tool.returnsInfobaseData());
    }

    @Test public void call_success_returnsOkWithoutError() throws Exception {
        UUID id = UUID.randomUUID();
        registerSession(id, mock(IBslStackFrame.class));
        when(evaluation.modifyVariable(any(), any(), any(), any(), anyInt()))
                .thenReturn(EvalOutcome.success("", ""));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args(id));

        assertEquals(true, out.get("ok"));
        assertNull(out.get("error"));
    }

    @Test public void call_failure_returnsOkFalseWithError() throws Exception {
        UUID id = UUID.randomUUID();
        registerSession(id, mock(IBslStackFrame.class));
        when(evaluation.modifyVariable(any(), any(), any(), any(), anyInt()))
                .thenReturn(EvalOutcome.failure("modify error: Переменная не определена"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args(id));

        assertEquals(false, out.get("ok"));
        assertTrue(((String) out.get("error")).contains("Переменная не определена"));
    }

    @Test public void call_forwardsFrameNameAndExpressionToEvaluationService() throws Exception {
        UUID id = UUID.randomUUID();
        IBslStackFrame frame = mock(IBslStackFrame.class);
        DebugSession session = registerSession(id, frame);
        when(evaluation.modifyVariable(any(), any(), any(), any(), anyInt()))
                .thenReturn(EvalOutcome.success("", ""));

        tool.call(args(id));

        verify(evaluation).modifyVariable(eq(session.target()), eq(frame),
                eq("Счётчик"), eq("42"), anyInt());
    }

    @Test public void call_unknownSession_throwsToolException() {
        try {
            tool.call(args(UUID.randomUUID()));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    @Test public void call_missingRequiredArg_throwsToolException() throws Exception {
        UUID id = UUID.randomUUID();
        registerSession(id, mock(IBslStackFrame.class));
        Map<String, Object> incomplete = args(id);
        incomplete.remove("valueExpression");
        try {
            tool.call(incomplete);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("valueExpression"));
        }
    }
}
