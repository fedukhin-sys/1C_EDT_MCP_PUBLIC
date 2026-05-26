package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationEngine;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationListener;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationResult;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvalOutcome;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvaluationService;

public class EvaluationServiceTest {

    private final EvaluationService service = new EvaluationService();

    /** A fake engine that fires the request's listener inline (the fast path). */
    private static void fireInline(IEvaluationEngine engine, IEvaluationResult result)
            throws Exception {
        doAnswer(inv -> {
            IEvaluationRequest req = inv.getArgument(0);
            try {
                req.getEvaluationListener().evaluationComplete(result);
            } catch (org.eclipse.debug.core.DebugException e) {
                throw new RuntimeException(e);
            }
            return null;
        }).when(engine).evaluateExpression(any(IEvaluationRequest.class));
    }

    @Test
    public void evaluate_successResult_returnsOkWithValueAndType() throws Exception {
        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getEvaluationEngine()).thenReturn(engine);
        IBslStackFrame frame = mock(IBslStackFrame.class);

        IEvaluationResult ok = mock(IEvaluationResult.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(ok.getResult().getErrorOccurred()).thenReturn(Boolean.FALSE);
        when(ok.getResult().getResultValueInfo().getTypeName()).thenReturn("Число");
        fireInline(engine, ok);

        EvalOutcome out = service.evaluate(target, frame, "1 + 1", 5);

        assertTrue(out.ok());
        assertEquals("Число", out.type());
        org.junit.Assert.assertNotNull(out.value());
    }

    @Test
    public void evaluate_errorResult_returnsNotOkWithError() throws Exception {
        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getEvaluationEngine()).thenReturn(engine);
        IBslStackFrame frame = mock(IBslStackFrame.class);

        IEvaluationResult err = mock(IEvaluationResult.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(err.getResult().getErrorOccurred()).thenReturn(Boolean.TRUE);
        when(err.getResult().getExceptionStr()).thenReturn("bad expr".getBytes());
        fireInline(engine, err);

        EvalOutcome out = service.evaluate(target, frame, "не выражение", 5);

        assertFalse(out.ok());
        // also confirms the UTF-8 decode of getExceptionStr() actually ran
        assertTrue(out.error().contains("bad expr"));
    }

    @Test
    public void evaluate_listenerNeverFires_returnsTimeoutError() throws Exception {
        IEvaluationEngine engine = mock(IEvaluationEngine.class); // evaluateExpression is a no-op
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getEvaluationEngine()).thenReturn(engine);
        IBslStackFrame frame = mock(IBslStackFrame.class);

        EvalOutcome out = service.evaluate(target, frame, "1", 1); // 1s timeout, no callback

        assertFalse(out.ok());
        assertTrue(out.error().toLowerCase().contains("timed out"));
    }

    @Test
    public void evaluate_listenerFiresFromAnotherThread_returnsResult() throws Exception {
        // The slow path: evaluateExpression returns immediately and the listener fires LATER
        // from a background thread (the notifyEvaluated case). This is exactly the path the
        // arm-latch-before-call discipline exists to make safe — the latch is armed before
        // the engine call, so a late count-down is still observed by await().
        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getEvaluationEngine()).thenReturn(engine);
        IBslStackFrame frame = mock(IBslStackFrame.class);

        IEvaluationResult ok = mock(IEvaluationResult.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(ok.getResult().getErrorOccurred()).thenReturn(Boolean.FALSE);
        when(ok.getResult().getResultValueInfo().getTypeName()).thenReturn("Число");
        doAnswer(inv -> {
            IEvaluationRequest req = inv.getArgument(0);
            IEvaluationListener l = req.getEvaluationListener();
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(100);
                    l.evaluationComplete(ok);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.setDaemon(true);
            t.start();
            return null;
        }).when(engine).evaluateExpression(any(IEvaluationRequest.class));

        EvalOutcome out = service.evaluate(target, frame, "1 + 1", 5);

        assertTrue(out.ok());
        assertEquals("Число", out.type());
    }
}
