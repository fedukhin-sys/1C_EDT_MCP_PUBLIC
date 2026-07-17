package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugException;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationEngine;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationResult;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IModificationRequest;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvalOutcome;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvaluationService;

/**
 * Тесты {@link EvaluationService#modifyVariable} — нативного канала присваивания EDT
 * ({@code IEvaluationEngine.modifyExpression}). Дополняют {@link EvaluationServiceTest},
 * который покрывает только чтение ({@code evaluateExpression}).
 */
public class EvaluationServiceModifyTest {

    private final EvaluationService service = new EvaluationService();

    private static IRuntimeDebugClientTarget targetWith(IEvaluationEngine engine) {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getEvaluationEngine()).thenReturn(engine);
        return target;
    }

    /** Движок, дёргающий listener запроса прямо в вызове (быстрый путь). */
    private static void fireInline(IEvaluationEngine engine, IEvaluationResult result)
            throws Exception {
        doAnswer(inv -> {
            IModificationRequest req = inv.getArgument(0);
            req.getEvaluationListener().evaluationComplete(result);
            return null;
        }).when(engine).modifyExpression(any(IModificationRequest.class));
    }

    @Test
    public void modify_successResult_returnsOk() throws Exception {
        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        IEvaluationResult ok = mock(IEvaluationResult.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(ok.getResult().getErrorOccurred()).thenReturn(Boolean.FALSE);
        fireInline(engine, ok);

        EvalOutcome out = service.modifyVariable(targetWith(engine), mock(IBslStackFrame.class),
                "Счётчик", "42", 5);

        assertTrue(out.ok());
    }

    @Test
    public void modify_emptyResult_treatedAsSuccess() throws Exception {
        // Часть реализаций modifyExpression фейерит listener без результата — это успех,
        // а не «движок промолчал».
        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        IEvaluationResult empty = mock(IEvaluationResult.class);
        when(empty.getResult()).thenReturn(null);
        fireInline(engine, empty);

        EvalOutcome out = service.modifyVariable(targetWith(engine), mock(IBslStackFrame.class),
                "Счётчик", "42", 5);

        assertTrue(out.ok());
    }

    @Test
    public void modify_errorResult_returnsErrorDecodedFromBytes() throws Exception {
        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        IEvaluationResult err = mock(IEvaluationResult.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(err.getResult().getErrorOccurred()).thenReturn(Boolean.TRUE);
        when(err.getResult().getExceptionStr())
                .thenReturn("Переменная не определена".getBytes("UTF-8"));
        fireInline(engine, err);

        EvalOutcome out = service.modifyVariable(targetWith(engine), mock(IBslStackFrame.class),
                "Нет", "1", 5);

        assertFalse(out.ok());
        assertTrue(out.error().contains("Переменная не определена"));
    }

    @Test
    public void modify_engineThrowsDebugException_returnsFailureNotThrow() throws Exception {
        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        doThrow(new DebugException(new Status(IStatus.ERROR, "test", "engine down")))
                .when(engine).modifyExpression(any(IModificationRequest.class));

        EvalOutcome out = service.modifyVariable(targetWith(engine), mock(IBslStackFrame.class),
                "Счётчик", "42", 5);

        assertFalse(out.ok());
        assertTrue(out.error().contains("engine down"));
    }

    @Test
    public void modify_listenerNeverFires_returnsTimeout() throws Exception {
        IEvaluationEngine engine = mock(IEvaluationEngine.class); // modifyExpression — no-op

        EvalOutcome out = service.modifyVariable(targetWith(engine), mock(IBslStackFrame.class),
                "Счётчик", "42", 1);

        assertFalse(out.ok());
        assertTrue(out.error().toLowerCase().contains("timed out"));
    }

    @Test
    public void modify_passesVariablePathAndExpressionToEngine() throws Exception {
        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        IEvaluationResult ok = mock(IEvaluationResult.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(ok.getResult().getErrorOccurred()).thenReturn(Boolean.FALSE);
        IBslStackFrame frame = mock(IBslStackFrame.class);

        java.util.concurrent.atomic.AtomicReference<IModificationRequest> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
            IModificationRequest req = inv.getArgument(0);
            captured.set(req);
            req.getEvaluationListener().evaluationComplete(ok);
            return null;
        }).when(engine).modifyExpression(any(IModificationRequest.class));

        service.modifyVariable(targetWith(engine), frame, "Объект.Реквизит", "\"текст\"", 5);

        IModificationRequest req = captured.get();
        assertEquals(frame, req.getStackFrame());
        assertEquals("\"текст\"", req.getExpression());
    }
}
