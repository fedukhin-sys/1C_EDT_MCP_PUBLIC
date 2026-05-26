package ru.fedukhin.edt.mcp.tools.debug.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts the internal debug DTO records into the {@code Map} shapes that {@code IMcpTool.call} returns. */
public final class DebugJson {

    private DebugJson() {}

    /** A {@link DebugStateDto} as a JSON-like map; {@code stoppedThread}/{@code location} omitted when null. */
    public static Map<String, Object> stateToMap(DebugStateDto dto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("debugSessionId", dto.debugSessionId());
        m.put("state", dto.state());
        m.put("timedOut", dto.timedOut());
        if (dto.stoppedThread() != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", dto.stoppedThread().id());
            t.put("name", dto.stoppedThread().name());
            m.put("stoppedThread", t);
        }
        if (dto.location() != null) {
            m.put("location", locationToMap(dto.location()));
        }
        return m;
    }

    /** A {@link BreakpointInfo} as a JSON-like map. */
    public static Map<String, Object> breakpointToMap(BreakpointInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("breakpointId", info.id());
        m.put("project", info.project());
        m.put("path", info.path());
        m.put("line", info.line());
        m.put("condition", info.condition());
        return m;
    }

    /** A {@link StackFrameDto} as a JSON-like map. */
    public static Map<String, Object> frameToMap(StackFrameDto dto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frameId", dto.frameId());
        m.put("project", dto.project());
        m.put("path", dto.path());
        m.put("line", dto.line());
        m.put("method", dto.method());
        return m;
    }

    /** A {@link VariableDto} as a JSON-like map. */
    public static Map<String, Object> variableToMap(VariableDto dto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", dto.name());
        m.put("type", dto.type());
        m.put("value", dto.value());
        return m;
    }

    private static Map<String, Object> locationToMap(SourceLocation loc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", loc.project());
        m.put("path", loc.path());
        m.put("line", loc.line());
        m.put("method", loc.method());
        return m;
    }
}
