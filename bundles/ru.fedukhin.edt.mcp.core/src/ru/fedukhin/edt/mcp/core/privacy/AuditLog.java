package ru.fedukhin.edt.mcp.core.privacy;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

/** Журнал обезличивания. Хранит МЕТА (что/сколько), но НЕ сами ПДн и НЕ token→значение. */
public final class AuditLog {

    private static final int CAP = 1000;
    private final Deque<Map<String, Object>> entries = new ArrayDeque<>();

    public synchronized void record(String tool, String infobaseKey, Sensitivity s,
                                    String objectOrType, int count) {
        if (count <= 0) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", Instant.now().toString());
        e.put("tool", tool);
        e.put("infobase", infobaseKey);
        e.put("sensitivity", s.name());
        e.put("object", objectOrType);
        e.put("count", count);
        entries.addLast(e);
        while (entries.size() > CAP) entries.removeFirst();
    }

    public synchronized List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> all = new ArrayList<>(entries);
        int from = Math.max(0, all.size() - limit);
        return new ArrayList<>(all.subList(from, all.size()));
    }
}
