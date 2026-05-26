package ru.fedukhin.edt.mcp.tools.debug;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointService;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugArgs;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugJson;

/** {@code set_breakpoint} — create a BSL line breakpoint (optionally conditional). */
public class SetBreakpointTool implements IMcpTool {

    private final BreakpointService service;

    @Inject
    public SetBreakpointTool(BreakpointService service) {
        this.service = service;
    }

    @Override public String name() { return "set_breakpoint"; }

    @Override public String description() {
        return "Set a BSL line breakpoint in a module (optionally with a condition expression)";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> integer = new LinkedHashMap<>(); integer.put("type", "integer");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", copy(str));
        properties.put("path", copy(str));
        properties.put("line", copy(integer));
        properties.put("condition", copy(str));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "path", "line"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        String project = DebugArgs.stringArg(args, "project");
        String path = DebugArgs.stringArg(args, "path");
        int line = DebugArgs.intArg(args, "line");
        String condition = DebugArgs.optStringArg(args, "condition");

        return DebugJson.breakpointToMap(service.setBreakpoint(project, path, line, condition));
    }

    private static Map<String, Object> copy(Map<String, Object> m) {
        return new LinkedHashMap<>(m);
    }
}
