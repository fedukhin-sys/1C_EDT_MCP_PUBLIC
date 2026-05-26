package ru.fedukhin.edt.mcp.tools.debug;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointService;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugArgs;

/** {@code remove_breakpoint} — remove a BSL line breakpoint by id (idempotent). */
public class RemoveBreakpointTool implements IMcpTool {

    private final BreakpointService service;

    @Inject
    public RemoveBreakpointTool(BreakpointService service) {
        this.service = service;
    }

    @Override public String name() { return "remove_breakpoint"; }

    @Override public String description() {
        return "Remove a BSL line breakpoint by breakpointId (no-op if it no longer exists)";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("breakpointId", str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("breakpointId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        String breakpointId = DebugArgs.stringArg(args, "breakpointId");
        service.remove(breakpointId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", true);
        return out;
    }
}
