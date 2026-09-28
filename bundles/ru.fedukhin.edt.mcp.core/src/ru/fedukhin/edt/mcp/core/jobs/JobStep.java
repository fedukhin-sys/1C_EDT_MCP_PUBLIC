package ru.fedukhin.edt.mcp.core.jobs;

import java.util.LinkedHashMap;
import java.util.Map;

/** Завершённый шаг задания: имя, итог, длительность и пояснение. */
public record JobStep(String name, StepStatus status, long durationMs, String message) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status.name());
        m.put("durationMs", durationMs);
        m.put("message", message);
        return m;
    }
}
