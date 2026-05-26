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

/** {@code debug_pause} — suspend the target; returns the resulting state. */
public class DebugPauseTool implements IMcpTool {

    private final DebugSessionRegistry registry;

    @Inject
    public DebugPauseTool(DebugSessionRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "debug_pause"; }

    @Override public String description() {
        return "Suspend a running debug session; returns the resulting state "
                + "(suspended, running if it timed out, or terminated)";
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
        return DebugJson.stateToMap(session.pause());
    }
}
