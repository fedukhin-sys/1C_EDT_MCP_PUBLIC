package ru.fedukhin.edt.mcp.core.privacy;

import java.util.Map;

/** Хранит per-infobase флаг «в базе есть реальные ПДн». Дефолт true (fail-closed). */
public final class InfobaseFlagStore {

    private final Map<String, Boolean> flags;
    private final PersistentFlagStore persistent;

    /** Хранение только в памяти процесса — используется тестами. */
    public InfobaseFlagStore(Map<String, Boolean> backing) {
        this.flags = backing;
        this.persistent = null;
    }

    /**
     * Хранение в файле, общем для всех инстанций EDT: без этого
     * {@code set_infobase_pii_flag} действовал бы в одной сессии из N.
     */
    public InfobaseFlagStore(PersistentFlagStore persistent) {
        this.flags = null;
        this.persistent = persistent;
    }

    public boolean containsRealPersonalData(String infobaseKey) {
        if (infobaseKey == null) return true;
        if (persistent != null) return persistent.containsRealPersonalData(infobaseKey);
        return flags.getOrDefault(infobaseKey, Boolean.TRUE);
    }

    public void setFlag(String infobaseKey, boolean value) {
        if (persistent != null) {
            persistent.setFlag(infobaseKey, value);
            return;
        }
        flags.put(infobaseKey, value);
    }
}
