package ru.fedukhin.edt.mcp.tools.debug;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugArgs;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;

/** {@code stop_debug} — terminate a debug session by id (idempotent). */
public class StopDebugTool implements IMcpTool {

    private final DebugSessionRegistry registry;

    @Inject
    public StopDebugTool(DebugSessionRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "stop_debug"; }

    @Override public String description() {
        return "Terminate a debug session by debugSessionId (no-op if it is already gone)";
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
        // remove() detaches the event listener (dispose()); terminate() kills the launch + client.
        registry.remove(id).ifPresent(session -> session.terminate());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stopped", true);
        return out;
    }
}
