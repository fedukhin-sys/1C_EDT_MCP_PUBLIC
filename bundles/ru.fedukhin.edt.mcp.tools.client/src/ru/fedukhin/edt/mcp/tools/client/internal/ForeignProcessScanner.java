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
 * <p>Чтение идёт через {@link ProcessHandle} — чистый JDK, без зависимостей. Но на
 * Windows он отдаёт только имя образа: {@code commandLine()} для ЧУЖОГО процесса пуст,
 * потому что JDK не читает его PEB. А информационная база живёт именно в командной
 * строке — без неё запись бесполезна: видно, что «что-то держит базу», но не какую.
 * Поэтому при пустой командной строке подключается разовый запрос к CIM.
 */
public final class ForeignProcessScanner {

    private static final Set<String> IMAGES = Set.of("1cv8.exe", "1cv8c.exe", "dbgs.exe");

    private ForeignProcessScanner() {}

    /**
     * @param ownPids идентификаторы процессов, уже известных собственному реестру
     * @return записи о чужих процессах платформы; пустой список, если ОС не даёт их видеть
     */
    public static List<Map<String, Object>> scan(Set<Long> ownPids) {
        return scan(ownPids, ForeignProcessScanner::windowsCommandLines);
    }

    /** Test seam: подстановка источника командных строк вместо запроса к ОС. */
    public static List<Map<String, Object>> scan(Set<Long> ownPids,
                                          java.util.function.Supplier<Map<Long, String>> fallback) {
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
        fillMissingInfobases(out, fallback);
        return out;
    }

    /**
     * Дозаполняет базы там, где {@link ProcessHandle} не дал командной строки.
     * Запрос делается один на весь скан и только если есть что дозаполнять.
     */
    private static void fillMissingInfobases(List<Map<String, Object>> out,
                                             java.util.function.Supplier<Map<Long, String>> fallback) {
        if (fallback == null) return;
        boolean needed = out.stream().anyMatch(e -> e.get("infobase") == null);
        if (!needed) return;
        Map<Long, String> byPid;
        try {
            byPid = fallback.get();
        } catch (RuntimeException e) {
            return;
        }
        if (byPid == null || byPid.isEmpty()) return;
        for (Map<String, Object> e : out) {
            if (e.get("infobase") != null) continue;
            String cl = byPid.get((Long) e.get("pid"));
            if (cl != null) e.put("infobase", infobaseOf(cl));
        }
    }

    /** Максимум ожидания разового запроса: инструмент диагностический, но висеть не должен. */
    private static final int CIM_TIMEOUT_SECONDS = 15;

    /**
     * Командные строки процессов платформы через CIM. Вне Windows и при любой осечке —
     * пустая карта: поле {@code infobase} просто останется незаполненным.
     */
    static Map<Long, String> windowsCommandLines() {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase(Locale.ROOT).startsWith("windows")) return Map.of();
        String filter = IMAGES.stream().map(i -> "Name='" + i + "'")
            .collect(java.util.stream.Collectors.joining(" or "));
        String script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; "
            + "Get-CimInstance Win32_Process -Filter \"" + filter + "\" | "
            + "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }";
        Process p = null;
        try {
            p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(false)
                .start();
            Map<Long, String> byPid = new LinkedHashMap<>();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('	');
                    if (tab <= 0) continue;
                    try {
                        byPid.put(Long.parseLong(line.substring(0, tab).trim()), line.substring(tab + 1));
                    } catch (NumberFormatException ignored) {
                        /* чужая строка в выводе — пропускаем */
                    }
                }
            }
            if (!p.waitFor(CIM_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Map.of();
            }
            return byPid;
        } catch (java.io.IOException | RuntimeException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
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
