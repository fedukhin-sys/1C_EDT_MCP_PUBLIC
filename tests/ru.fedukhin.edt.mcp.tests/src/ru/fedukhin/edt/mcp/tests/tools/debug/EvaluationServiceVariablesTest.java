package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationEngine;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationListener;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvaluationService;
import ru.fedukhin.edt.mcp.tools.debug.internal.VariableDto;

public class EvaluationServiceVariablesTest {

    private final EvaluationService service = new EvaluationService();

    private static IBslVariable var(String name, String type, String detail) throws Exception {
        IBslValue value = mock(IBslValue.class);
        when(value.getValueTypeName()).thenReturn(type);
        when(value.getDetailString()).thenReturn(detail);
        IBslVariable v = mock(IBslVariable.class);
        when(v.getName()).thenReturn(name);
        when(v.getValue()).thenReturn(value);
        when(v.isEvaluated()).thenReturn(true);
        return v;
    }

    /**
     * Makes {@code engine.evaluateVariables(frame, listener)} fire the listener inline (the fast
     * path). {@code evaluationComplete} declares {@code throws DebugException}, so the {@code
     * doAnswer} lambda must wrap it. Mirrors {@code EvaluationServiceTest.fireInline}.
     */
    private static void fireVariablesInline(IEvaluationEngine engine) throws Exception {
        doAnswer(inv -> {
            IEvaluationListener l = inv.getArgument(1);
            try {
                l.evaluationComplete(null); // payload unused by readVariables — it re-reads the frame
            } catch (org.eclipse.debug.core.DebugException e) {
                throw new RuntimeException(e);
            }
            return null;
        }).when(engine).evaluateVariables(any(IBslStackFrame.class), any(IEvaluationListener.class));
    }

    @Test
    public void readVariables_drivesEvaluateVariablesThenConvertsFrameVariables() throws Exception {
        IBslVariable counter = var("Счётчик", "Число", "7");
        IBslVariable name = var("Имя", "Строка", "Привет");
        IBslStackFrame frame = mock(IBslStackFrame.class);
        when(frame.getVariables()).thenReturn(new IBslVariable[] { counter, name });

        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        fireVariablesInline(engine);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getEvaluationEngine()).thenReturn(engine);

        List<VariableDto> vars = service.readVariables(target, frame, 5);

        assertEquals(2, vars.size());
        assertEquals("Счётчик", vars.get(0).name());
        assertEquals("Число", vars.get(0).type());
        assertEquals("7", vars.get(0).value());
        assertEquals("Имя", vars.get(1).name());
    }

    @Test
    public void readVariables_noVariables_returnsEmptyList() throws Exception {
        IBslStackFrame frame = mock(IBslStackFrame.class);
        when(frame.getVariables()).thenReturn(new IBslVariable[0]);
        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        fireVariablesInline(engine);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getEvaluationEngine()).thenReturn(engine);

        assertEquals(0, service.readVariables(target, frame, 5).size());
    }

    @Test
    public void readVariables_listenerFiresFromAnotherThread_returnsVariables() throws Exception {
        // Slow path: evaluateVariables returns immediately, the listener fires LATER from a
        // background thread — the arm-latch-before-call discipline must still catch it.
        IBslVariable counter = var("Счётчик", "Число", "7");
        IBslStackFrame frame = mock(IBslStackFrame.class);
        when(frame.getVariables()).thenReturn(new IBslVariable[] { counter });

        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        doAnswer(inv -> {
            IEvaluationListener l = inv.getArgument(1);
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(100);
                    l.evaluationComplete(null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.setDaemon(true);
            t.start();
            return null;
        }).when(engine).evaluateVariables(any(IBslStackFrame.class), any(IEvaluationListener.class));
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getEvaluationEngine()).thenReturn(engine);

        List<VariableDto> vars = service.readVariables(target, frame, 5);

        assertEquals(1, vars.size());
        assertEquals("Счётчик", vars.get(0).name());
    }

    @Test
    public void readVariables_unevaluatedVariable_degradesToEmptyValue() throws Exception {
        // A variable still not evaluated after evaluateVariables (getValue() == null) must
        // degrade gracefully to an empty value rather than NPE. (The !isEvaluated() catch-up
        // itself is a deferred follow-up per the plan's Self-Review; this pins the fallback.)
        IBslVariable unevaluated = mock(IBslVariable.class);
        when(unevaluated.getName()).thenReturn("ПозжеВычислится");
        when(unevaluated.getValue()).thenReturn(null);
        IBslStackFrame frame = mock(IBslStackFrame.class);
        when(frame.getVariables()).thenReturn(new IBslVariable[] { unevaluated });
        IEvaluationEngine engine = mock(IEvaluationEngine.class);
        fireVariablesInline(engine);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getEvaluationEngine()).thenReturn(engine);

        List<VariableDto> vars = service.readVariables(target, frame, 5);

        assertEquals(1, vars.size());
        assertEquals("ПозжеВычислится", vars.get(0).name());
        assertEquals("", vars.get(0).value());
    }
}
