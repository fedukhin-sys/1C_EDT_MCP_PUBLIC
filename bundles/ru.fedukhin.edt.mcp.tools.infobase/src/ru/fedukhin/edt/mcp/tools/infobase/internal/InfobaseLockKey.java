package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.Locale;

/**
 * Ключ межпроцессного замка для монопольных операций над информационной базой.
 *
 * <p><b>Почему ключ — база, а не проект и не рабочая область.</b> Один проект
 * деплоится в разные базы, разные расширения деплоятся в одну базу, а
 * конфликтуют они именно по базе. Проектная сторона к тому же уже эксклюзивна
 * межпроцессно: Eclipse держит OS-lock на {@code .metadata/.lock}, два IDE один
 * workspace не откроют.
 *
 * <p>Приоритет источников: uuid базы (стабилен и не зависит от написания строки
 * подключения) → нормализованная строка подключения → имя. Имя — последнее
 * средство: оно локально для списка баз пользователя.
 */
public final class InfobaseLockKey {

    private InfobaseLockKey() {}

    public static String of(InfobaseReference ref) {
        if (ref == null) return "ib:unknown";
        return build(uuidOf(ref), connectionOf(ref), ref.getName());
    }

    /** Тестируемое ядро: чистая функция от трёх возможных источников. */
    public static String build(String uuid, String connectionString, String name) {
        if (uuid != null && !uuid.isBlank()) return "ib:uuid:" + uuid;
        if (connectionString != null && !connectionString.isBlank()) {
            return "ib:conn:" + connectionString.toLowerCase(Locale.ROOT).replace(" ", "");
        }
        return "ib:name:" + name;
    }

    private static String uuidOf(InfobaseReference ref) {
        try {
            java.util.UUID uuid = ref.getUuid();
            return uuid == null ? null : uuid.toString();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String connectionOf(InfobaseReference ref) {
        try {
            com._1c.g5.v8.dt.platform.services.model.IConnectionString cs = ref.getConnectionString();
            return cs == null ? null : cs.asConnectionString();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
