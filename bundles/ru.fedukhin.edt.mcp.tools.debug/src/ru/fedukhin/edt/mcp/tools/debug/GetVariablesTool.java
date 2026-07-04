package ru.fedukhin.edt.mcp.tools.debug;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugArgs;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugJson;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvaluationService;
import ru.fedukhin.edt.mcp.tools.debug.internal.VariableDto;

/** {@code get_variables} — the variables of a frame in a suspended debug session. */
public class GetVariablesTool implements IMcpTool {

    /** Variable evaluation round-trips to the dbgs server; 30s is generous for a frame's locals. */
    private static final int TIMEOUT_SECONDS = 30;

    private final DebugSessionRegistry registry;
    private final EvaluationService evaluation;

    @Inject
    public GetVariablesTool(DebugSessionRegistry registry, EvaluationService evaluation) {
        this.registry = registry;
        this.evaluation = evaluation;
    }

    @Override public String name() { return "get_variables"; }

    @Override public String description() {
        return "Get the variables of a frame in a suspended debug session (name, type, value)";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("debugSessionId", new LinkedHashMap<>(str));
        properties.put("frameId", new LinkedHashMap<>(str));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("debugSessionId", "frameId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        UUID id = DebugArgs.uuidArg(args, "debugSessionId");
        String frameId = DebugArgs.stringArg(args, "frameId");

        DebugSession session = registry.require(id);
        IBslStackFrame frame = session.findFrame(frameId);
        List<VariableDto> vars = evaluation.readVariables(session.target(), frame, TIMEOUT_SECONDS);

        List<Map<String, Object>> out = new ArrayList<>();
        for (VariableDto v : vars) {
            out.add(DebugJson.variableToMap(v));
        }
        return out;
    }

    @Override public boolean returnsInfobaseData() { return true; }
}
