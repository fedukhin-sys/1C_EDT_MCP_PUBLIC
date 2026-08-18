package ru.fedukhin.edt.mcp.core.privacy;

import java.util.concurrent.ConcurrentHashMap;

/** Процесс-синглтоны разделяемого состояния обезличивания (общие для инжекторов core и tools.privacy). */
public final class PrivacyState {
    private static volatile InfobaseFlagStore flags;
    private static volatile AuditLog audit;
    private PrivacyState() {}

    public static InfobaseFlagStore flags() {
        InfobaseFlagStore f = flags;
        if (f == null) {
            synchronized (PrivacyState.class) {
                f = flags;
                // Файловое хранилище, а не память процесса: флаг должен действовать
                // во всех инстанциях EDT, а не только в той, где его выставили.
                if (f == null) { f = new InfobaseFlagStore(new PersistentFlagStore()); flags = f; }
            }
        }
        return f;
    }

    public static AuditLog audit() {
        AuditLog a = audit;
        if (a == null) {
            synchronized (PrivacyState.class) {
                a = audit;
                if (a == null) { a = new AuditLog(true); audit = a; }
            }
        }
        return a;
    }
}
