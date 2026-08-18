package ru.fedukhin.edt.mcp.tools.client.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Процессы платформы 1С, поднятые не этой инстанцией EDT.
 *
 * <p><b>Зачем.</b> {@link ClientProcessRegistry} хранит живой {@link Process} и
 * между JVM непереносим в принципе: {@code list_running_clients} не видит клиент,
 * запущенный соседней инстанцией, а {@code stop_client} не может его погасить.
 * При этом осиротевший 1cv8 держит информационную базу и превращает чужой
 * {@code deploy_project} в тихий no-op — диагностировать это через MCP было нечем.
 *
 * <p>Чтение идёт через {@link ProcessHandle} — чистый JDK, без зависимостей.
 */
public final class ForeignProcessScanner {

    private static final Set<String> IMAGES = Set.of("1cv8.exe", "1cv8c.exe", "dbgs.exe");

    private ForeignProcessScanner() {}

    /**
     * @param ownPids идентификаторы процессов, уже известных собственному реестру
     * @return записи о чужих процессах платформы; пустой список, если ОС не даёт их видеть
     */
    public static List<Map<String, Object>> scan(Set<Long> ownPids) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            ProcessHandle.allProcesses().forEach(h -> {
                if (ownPids != null && ownPids.contains(h.pid())) return;
                String image = imageOf(h);
                if (image == null || !IMAGES.contains(image)) return;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("pid", h.pid());
                e.put("image", image);
                e.put("infobase", infobaseOf(h.info().commandLine().orElse(null)));
                e.put("startedAt", h.info().startInstant().map(Instant::toString).orElse(null));
                e.put("foreign", true);
                out.add(e);
            });
        } catch (RuntimeException e) {
            // Инвентаризация — диагностика, а не условие работы инструмента.
            return out;
        }
        return out;
    }

    /** Имя образа без пути и в нижнем регистре; null — процесс не даёт себя рассмотреть. */
    static String imageOf(ProcessHandle h) {
        String cmd = h.info().command().orElse(null);
        if (cmd == null || cmd.isBlank()) return null;
        String normalized = cmd.replace('/', '\\');
        int slash = normalized.lastIndexOf('\\');
        return normalized.substring(slash + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Достаёт путь или адрес базы из ключей {@code /F} и {@code /S} командной строки
     * 1cv8. Возвращает null, если ключей нет — например, у DESIGNER без базы.
     */
    public static String infobaseOf(String commandLine) {
        if (commandLine == null) return null;
        for (String key : new String[] { "/F", "/S" }) {
            int i = commandLine.indexOf(key);
            if (i < 0) continue;
            String tail = commandLine.substring(i + key.length()).trim();
            if (tail.startsWith("\"")) {
                int end = tail.indexOf('"', 1);
                if (end > 0) return tail.substring(1, end);
                continue;
            }
            int end = tail.indexOf(' ');
            String value = (end < 0) ? tail : tail.substring(0, end);
            if (!value.isBlank()) return value;
        }
        return null;
    }
}
