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
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;

/** {@code get_debug_state} — a non-blocking snapshot of a debug session's state. */
public class GetDebugStateTool implements IMcpTool {

    private final DebugSessionRegistry registry;

    @Inject
    public GetDebugStateTool(DebugSessionRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "get_debug_state"; }

    @Override public String description() {
        return "Get the current state (running / suspended / terminated) of a debug session";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("debugSessionId", str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("debugSessionId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        UUID id = DebugArgs.uuidArg(args, "debugSessionId");
        DebugSession session = registry.require(id);
        return DebugJson.stateToMap(session.getState());
    }
}
