package ru.fedukhin.edt.mcp.core.privacy;

import java.util.Map;

/** Хранит per-infobase флаг «в базе есть реальные ПДн». Дефолт true (fail-closed). */
public final class InfobaseFlagStore {

    private final Map<String, Boolean> flags;

    public InfobaseFlagStore(Map<String, Boolean> backing) {
        this.flags = backing;
    }

    public boolean containsRealPersonalData(String infobaseKey) {
        if (infobaseKey == null) return true;
        return flags.getOrDefault(infobaseKey, Boolean.TRUE);
    }

    public void setFlag(String infobaseKey, boolean value) {
        flags.put(infobaseKey, value);
    }
}
