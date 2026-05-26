package ru.fedukhin.edt.mcp.tools.debug;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointInfo;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointService;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugJson;

/** {@code list_breakpoints} — list all BSL line breakpoints registered in the workspace. */
public class ListBreakpointsTool implements IMcpTool {

    private final BreakpointService service;

    @Inject
    public ListBreakpointsTool(BreakpointService service) {
        this.service = service;
    }

    @Override public String name() { return "list_breakpoints"; }

    @Override public String description() {
        return "List all BSL line breakpoints currently set in the workspace";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BreakpointInfo info : service.list()) {
            out.add(DebugJson.breakpointToMap(info));
        }
        return out;
    }
}
