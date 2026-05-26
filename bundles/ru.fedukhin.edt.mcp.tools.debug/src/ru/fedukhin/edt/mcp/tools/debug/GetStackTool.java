package ru.fedukhin.edt.mcp.tools.debug;

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
import ru.fedukhin.edt.mcp.tools.debug.internal.StackFrameDto;

/** {@code get_stack} — the call stack of a thread in a suspended debug session. */
public class GetStackTool implements IMcpTool {

    private final DebugSessionRegistry registry;

    @Inject
    public GetStackTool(DebugSessionRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "get_stack"; }

    @Override public String description() {
        return "Get the call stack of a thread in a suspended debug session";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("debugSessionId", new LinkedHashMap<>(str));
        properties.put("threadId", new LinkedHashMap<>(str));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("debugSessionId", "threadId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        UUID id = DebugArgs.uuidArg(args, "debugSessionId");
        String threadId = DebugArgs.stringArg(args, "threadId");
        DebugSession session = registry.require(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (StackFrameDto frame : session.getStack(threadId)) {
            out.add(DebugJson.frameToMap(frame));
        }
        return out;
    }
}
