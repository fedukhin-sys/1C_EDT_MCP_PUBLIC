package ru.fedukhin.edt.mcp.tools.debug;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugArgs;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvalOutcome;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvaluationService;

/**
 * {@code set_variable} — присвоить значение переменной в кадре стека через
 * {@code IEvaluationEngine.modifyExpression(IModificationRequest)} — нативный канал
 * EDT для присваивания. Поддерживает любое выражение, которое умеет вычислить
 * движок (литералы, ссылки, простые выражения).
 *
 * <p>Plan A (evaluate-as-statement) не работал — BSL evaluate-engine трактует {@code =}
 * как сравнение, а не присваивание.
 * Plan B v2 (IVariable.setValue через frame.getVariables) спотыкался об гонку: после
 * {@code engine.evaluateVariables(...)} listener фейерил раньше, чем массив переменных
 * реально заполнялся — {@code frame.getVariables()} возвращал placeholder
 * «(вычисление локальных переменных)».
 */
public class SetVariableTool implements IMcpTool {

    private static final int TIMEOUT_SECONDS = 30;

    private final DebugSessionRegistry registry;
    private final EvaluationService evaluation;

    @Inject
    public SetVariableTool(DebugSessionRegistry registry, EvaluationService evaluation) {
        this.registry = registry;
        this.evaluation = evaluation;
    }

    @Override public String name() { return "set_variable"; }

    @Override public String description() {
        return "Set the value of a BSL variable in a frame of a suspended debug session";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("debugSessionId", new LinkedHashMap<>(str));
        properties.put("frameId", new LinkedHashMap<>(str));
        properties.put("variableName", new LinkedHashMap<>(str));
        properties.put("valueExpression", new LinkedHashMap<>(str));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("debugSessionId", "frameId", "variableName", "valueExpression"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        UUID id = DebugArgs.uuidArg(args, "debugSessionId");
        String frameId = DebugArgs.stringArg(args, "frameId");
        String variableName = DebugArgs.stringArg(args, "variableName");
        String valueExpression = DebugArgs.stringArg(args, "valueExpression");

        DebugSession session = registry.require(id);
        IBslStackFrame frame = session.findFrame(frameId);

        EvalOutcome outcome = evaluation.modifyVariable(session.target(), frame,
                variableName, valueExpression, TIMEOUT_SECONDS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", outcome.ok());
        if (!outcome.ok()) {
            out.put("error", outcome.error());
        }
        return out;
    }

    /** Поле error приходит от debug-движка и содержит фрагменты значений базы. */
    @Override public boolean returnsInfobaseData() { return true; }
}
