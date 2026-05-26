package ru.fedukhin.edt.mcp.tools.debug;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugArgs;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugJson;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession.StepKind;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;

/** {@code debug_step} — step a thread (over / into / out); blocks until the step completes or times out. */
public class DebugStepTool implements IMcpTool {

    private final DebugSessionRegistry registry;

    @Inject
    public DebugStepTool(DebugSessionRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "debug_step"; }

    @Override public String description() {
        return "Step a thread in a suspended debug session (over / into / out); "
                + "blocks until the step completes or timeoutSeconds elapses";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string"); kind.put("enum", List.of("over", "into", "out"));
        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer"); timeout.put("minimum", 1); timeout.put("maximum", 300);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("debugSessionId", new LinkedHashMap<>(str));
        properties.put("threadId", new LinkedHashMap<>(str));
        properties.put("kind", kind);
        properties.put("timeoutSeconds", timeout);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("debugSessionId", "threadId", "kind"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        UUID id = DebugArgs.uuidArg(args, "debugSessionId");
        String threadId = DebugArgs.stringArg(args, "threadId");
        String kindStr = DebugArgs.stringArg(args, "kind");
        int timeoutSeconds = DebugArgs.intArg(args, "timeoutSeconds", 30, 1, 300);
        StepKind kind = switch (kindStr) {
            case "over" -> StepKind.OVER;
            case "into" -> StepKind.INTO;
            case "out"  -> StepKind.OUT;
            default -> throw new ToolException("unknown step kind: '" + kindStr
                    + "' (expected over | into | out)");
        };
        DebugSession session = registry.require(id);
        return DebugJson.stateToMap(session.step(threadId, kind, timeoutSeconds));
    }
}
