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

/** {@code debug_resume} — resume the target; blocks until the next stop or timeout. */
public class DebugResumeTool implements IMcpTool {

    private final DebugSessionRegistry registry;

    @Inject
    public DebugResumeTool(DebugSessionRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "debug_resume"; }

    @Override public String description() {
        return "Resume a debug session; blocks until the next stop (breakpoint / termination) "
                + "or timeoutSeconds elapses";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer"); timeout.put("minimum", 1); timeout.put("maximum", 300);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("debugSessionId", str);
        properties.put("timeoutSeconds", timeout);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("debugSessionId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        UUID id = DebugArgs.uuidArg(args, "debugSessionId");
        int timeoutSeconds = DebugArgs.intArg(args, "timeoutSeconds", 30, 1, 300);
        DebugSession session = registry.require(id);
        return DebugJson.stateToMap(session.resume(timeoutSeconds));
    }
}
