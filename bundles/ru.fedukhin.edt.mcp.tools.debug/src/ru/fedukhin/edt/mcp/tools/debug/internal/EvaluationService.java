package ru.fedukhin.edt.mcp.tools.debug.internal;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationEngine;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationListener;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationResult;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.model.calculations.BaseValueInfoData;
import com._1c.g5.v8.dt.debug.model.calculations.CalculationResultBaseData;
import com._1c.g5.v8.dt.debug.model.calculations.ViewInterface;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.debug.core.DebugException;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Synchronous wrapper over the async EDT {@link IEvaluationEngine}. The engine fires its
 * {@link IEvaluationListener} either inline on the calling thread (fast path) or later from the
 * HTTP event-dispatch job ({@code notifyEvaluated}, slow path) — so the latch is armed and the
 * listener wired into the request BEFORE the engine call (same discipline as {@link DebugSession}).
 *
 * <p>Pure async→sync glue with no stored state — safe to share. Mock-unit-tested; the live path
 * is covered by the manual IDE smoke test (spec §10.5).
 */
public class EvaluationService {

    /** Max value-presentation size; name matches EvaluationRequestBuilder#setMaxTestSize. */
    private static final int MAX_TEST_SIZE = 1000;

    @Inject
    public EvaluationService() {
    }

    /** Evaluate a BSL {@code expression} in {@code frame}; blocks up to {@code timeoutSeconds}. */
    public EvalOutcome evaluate(IRuntimeDebugClientTarget target, IBslStackFrame frame,
                                String expression, int timeoutSeconds) throws ToolException {
        IEvaluationEngine engine = target.getEvaluationEngine();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<IEvaluationResult> box = new AtomicReference<>();
        IEvaluationListener listener = result -> { box.set(result); latch.countDown(); };

        IEvaluationRequest request = EvaluationRequest.builder(new BslValuePath(expression))
                .setStackFrame(frame)
                .setExpressionUuid(UUID.randomUUID())
                .setInterface(ViewInterface.NONE)
                .setMaxTestSize(MAX_TEST_SIZE)
                .setMultiLine(false)
                .setEvaluationListener(listener)
                .build();

        try {
            engine.evaluateExpression(request);   // listener may already have fired inline by now
        } catch (DebugException e) {
            // DebugException is a CoreException — its IStatus carries the server-side detail.
            return EvalOutcome.failure("evaluation failed: " + e.getStatus().getMessage());
        }
        boolean done = awaitQuietly(latch, timeoutSeconds);
        if (!done) {
            return EvalOutcome.failure("evaluation timed out after " + timeoutSeconds + "s");
        }
        return toEvalOutcome(box.get());
    }

    /**
     * Evaluate and return every variable of {@code frame} as a DTO. {@code IBslStackFrame
     * .getVariables()} is lazily filled — it returns placeholders until {@code evaluateVariables}
     * has run — so this drives {@link IEvaluationEngine#evaluateVariables} and awaits the listener
     * (inline fast path or {@code notifyEvaluated} slow path) before re-reading the frame.
     *
     * <p>Unlike {@link #evaluate} (where an engine failure is a user-facing {@code ok:false}
     * payload), a failure here is an infrastructure error and propagates as {@link ToolException}.
     */
    public List<VariableDto> readVariables(IRuntimeDebugClientTarget target, IBslStackFrame frame,
                                           int timeoutSeconds) throws ToolException {
        IEvaluationEngine engine = target.getEvaluationEngine();
        CountDownLatch latch = new CountDownLatch(1);
        IEvaluationListener listener = result -> latch.countDown();

        try {
            engine.evaluateVariables(frame, listener);   // listener may already have fired inline
        } catch (DebugException e) {
            throw new ToolException("reading variables failed: " + e.getStatus().getMessage(), e);
        }
        boolean done = awaitQuietly(latch, timeoutSeconds);
        if (!done) {
            throw new ToolException("reading variables timed out after " + timeoutSeconds + "s");
        }

        DebugStateReader reader = new DebugStateReader();
        List<VariableDto> out = new ArrayList<>();
        IBslVariable[] vars;
        try {
            vars = frame.getVariables();   // now filled with real variables
        } catch (Exception e) {
            // getVariables в EDT 2026.x больше не объявляет throws DebugException, поэтому узкий
            // catch (DebugException) стал бы «unreachable». Ловим Exception (достижим на обеих
            // версиях), сообщение извлекаем через messageOf — для DebugException это статус.
            throw new ToolException("reading variables failed: " + messageOf(e), e);
        }
        if (vars != null) {
            for (IBslVariable v : vars) {
                out.add(reader.toVariableDto(v));
            }
        }
        return out;
    }

    /**
     * Присваивает значение переменной кадра через {@code IEvaluationEngine.modifyExpression}.
     * {@code variablePath} — имя переменной (для топ-уровневых локалов) или путь
     * (например, {@code "СтруктураX.Поле"}). {@code valueExpression} — BSL-выражение
     * справа от {@code =}: литерал, ссылка на другую переменную, простое выражение.
     */
    public EvalOutcome modifyVariable(IRuntimeDebugClientTarget target, IBslStackFrame frame,
                                      String variablePath, String valueExpression, int timeoutSeconds)
            throws ToolException {
        IEvaluationEngine engine = target.getEvaluationEngine();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<IEvaluationResult> box = new AtomicReference<>();
        IEvaluationListener listener = result -> { box.set(result); latch.countDown(); };

        com._1c.g5.v8.dt.debug.core.model.evaluation.ModificationRequest request =
                new com._1c.g5.v8.dt.debug.core.model.evaluation.ModificationRequest(
                        frame, UUID.randomUUID(), new BslValuePath(variablePath),
                        valueExpression, listener);

        try {
            engine.modifyExpression(request);
        } catch (DebugException e) {
            return EvalOutcome.failure("modify failed: " + e.getStatus().getMessage());
        }
        boolean done = awaitQuietly(latch, timeoutSeconds);
        if (!done) {
            return EvalOutcome.failure("modify timed out after " + timeoutSeconds + "s");
        }
        IEvaluationResult result = box.get();
        if (result == null || result.getResult() == null) {
            // listener фейернул, но результат пустой — считаем что успех
            // (некоторые modifyExpression-реализации не возвращают значение).
            return EvalOutcome.success("", "");
        }
        CalculationResultBaseData data = result.getResult();
        if (Boolean.TRUE.equals(data.getErrorOccurred())) {
            return EvalOutcome.failure(decode(data.getExceptionStr(), "modify error"));
        }
        return EvalOutcome.success("", "");
    }

    private static EvalOutcome toEvalOutcome(IEvaluationResult result) {
        if (result == null || result.getResult() == null) {
            return EvalOutcome.failure("evaluation returned no result");
        }
        CalculationResultBaseData data = result.getResult();
        if (Boolean.TRUE.equals(data.getErrorOccurred())) {
            return EvalOutcome.failure(decode(data.getExceptionStr(), "evaluation error"));
        }
        BaseValueInfoData info = data.getResultValueInfo();
        if (info == null) {
            return EvalOutcome.failure("evaluation returned no value");
        }
        String type = info.getTypeName();
        // getPres() is EDT's ready-made human-readable presentation; getValueString() is the raw
        // value. Prefer the presentation, fall back to the value string.
        String value = decode(info.getPres(), null);
        if (value == null) {
            value = decode(info.getValueString(), "");
        }
        return EvalOutcome.success(value, type);
    }

    private static String decode(byte[] bytes, String dflt) {
        if (bytes == null) {
            return dflt;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean awaitQuietly(CountDownLatch latch, int timeoutSeconds)
            throws ToolException {
        try {
            return latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("interrupted waiting for evaluation");
        }
    }

    /**
     * Извлекает человекочитаемое сообщение из пойманного {@link Exception}. Для
     * {@link DebugException} берётся сообщение его {@code IStatus} (как в остальных ветках),
     * иначе — {@link Throwable#getMessage()}. Используется там, где catch расширен до Exception
     * ради совместимости с версиями EDT, где метод больше не объявляет throws DebugException.
     */
    private static String messageOf(Exception e) {
        if (e instanceof DebugException de && de.getStatus() != null) {
            return de.getStatus().getMessage();
        }
        return e.getMessage();
    }
}
