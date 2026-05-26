package ru.fedukhin.edt.mcp.tools.debug;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;

/** {@code list_debug_sessions} — list active debug sessions (prunes terminated ones). */
public class ListDebugSessionsTool implements IMcpTool {

    private final DebugSessionRegistry registry;

    @Inject
    public ListDebugSessionsTool(DebugSessionRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "list_debug_sessions"; }

    @Override public String description() {
        return "List active debug sessions (debugSessionId, state, infobase, clientType, startedAt)";
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
        for (DebugSession s : registry.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("debugSessionId", s.id().toString());
            m.put("state", s.getState().state());
            m.put("infobase", s.infobase());
            m.put("clientType", s.clientType());
            m.put("startedAt", s.startedAt().toString());
            out.add(m);
        }
        return out;
    }
}
