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

/** {@code evaluate} — evaluate a BSL expression in a frame of a suspended debug session. */
public class EvaluateTool implements IMcpTool {

    /** An expression round-trips to the dbgs server; 30s matches get_variables. */
    private static final int TIMEOUT_SECONDS = 30;

    private final DebugSessionRegistry registry;
    private final EvaluationService evaluation;

    @Inject
    public EvaluateTool(DebugSessionRegistry registry, EvaluationService evaluation) {
        this.registry = registry;
        this.evaluation = evaluation;
    }

    @Override public String name() { return "evaluate"; }

    @Override public String description() {
        return "Evaluate a BSL expression in a frame of a suspended debug session";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("debugSessionId", new LinkedHashMap<>(str));
        properties.put("frameId", new LinkedHashMap<>(str));
        properties.put("expression", new LinkedHashMap<>(str));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("debugSessionId", "frameId", "expression"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        UUID id = DebugArgs.uuidArg(args, "debugSessionId");
        String frameId = DebugArgs.stringArg(args, "frameId");
        String expression = DebugArgs.stringArg(args, "expression");

        DebugSession session = registry.require(id);
        IBslStackFrame frame = session.findFrame(frameId);
        EvalOutcome outcome = evaluation.evaluate(session.target(), frame, expression, TIMEOUT_SECONDS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", outcome.ok());
        if (outcome.ok()) {
            out.put("value", outcome.value());
            out.put("type", outcome.type());
        } else {
            out.put("error", outcome.error());
        }
        return out;
    }
}
