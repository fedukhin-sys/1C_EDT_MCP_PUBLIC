package ru.fedukhin.edt.mcp.core.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import ru.fedukhin.edt.mcp.core.ipc.McpHome;

/**
 * Журнал обезличивания. Хранит МЕТА (что/сколько), но НЕ сами ПДн и НЕ token→значение.
 *
 * <p>В персистентном режиме каждая инстанция 1C:EDT пишет свой файл
 * {@code ~/.edt-mcp/privacy/audit-&lt;pid&gt;.jsonl}, а {@link #recent(int)} сливает
 * файлы всех инстанций. Без этого {@code get_privacy_audit} показывал бы только
 * свою долю фактов обезличивания — для журнала, который существует ради 152-ФЗ,
 * это делает его бесполезным при нескольких параллельных сессиях.
 *
 * <p>Файл на процесс, а не общий: так между инстанциями нет конкуренции и не нужен
 * замок. Записи по построению не содержат ПДн, поэтому хранение на диске допустимо.
 */
public final class AuditLog {

    private static final int CAP = 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration FILE_TTL = Duration.ofDays(30);
    private static final String FILE_PREFIX = "audit-";

    private final Deque<Map<String, Object>> entries = new ArrayDeque<>();
    private final boolean persistent;

    /** Только память процесса. Используется тестами и как безопасный дефолт. */
    public AuditLog() {
        this(false);
    }

    /**
     * @param persistent писать и читать файлы инстанций; включается в
     *     {@link PrivacyState}, чтобы журнал был общим для всех сессий
     */
    public AuditLog(boolean persistent) {
        this.persistent = persistent;
        if (persistent) purgeStaleFiles();
    }

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
        if (persistent) append(e);
    }

    /**
     * Последние {@code limit} записей. В персистентном режиме — по всем инстанциям
     * сразу; собственные записи читаются из своего же файла, поэтому память тут не
     * подмешивается (иначе они удвоились бы).
     */
    public synchronized List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> all = persistent ? readAllFiles() : new ArrayList<>(entries);
        all.sort(Comparator.comparing(m -> String.valueOf(m.get("at"))));
        int from = Math.max(0, all.size() - limit);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    private static Path ownFile() {
        return McpHome.privacy().resolve(FILE_PREFIX + ProcessHandle.current().pid() + ".jsonl");
    }

    /** Отказ записи не должен ронять вызов инструмента — журнал вспомогательный. */
    private static void append(Map<String, Object> entry) {
        try {
            Path f = ownFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, MAPPER.writeValueAsString(entry) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            /* best-effort */
        }
    }

    private static List<Map<String, Object>> readAllFiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = McpHome.privacy();
        if (!Files.isDirectory(dir)) return out;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().startsWith(FILE_PREFIX)).toList();
        } catch (IOException e) {
            return out;
        }
        for (Path f : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    // Через Class, а не TypeReference: анонимный подкласс TypeReference
                    // под OSGi даёт loader constraint violation на вендоренном Jackson.
                    Map<?, ?> raw = MAPPER.readValue(line, LinkedHashMap.class);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> kv : raw.entrySet()) {
                        entry.put(String.valueOf(kv.getKey()), kv.getValue());
                    }
                    out.add(entry);
                } catch (IOException | RuntimeException ignored) {
                    /* битая строка — пропускаем, остальные читаются */
                }
            }
        }
        return out;
    }

    /** Журналы умерших инстанций копились бы вечно. */
    private static void purgeStaleFiles() {
        Path dir = McpHome.privacy();
        if (!Files.isDirectory(dir)) return;
        Instant cutoff = Instant.now().minus(FILE_TTL);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().startsWith(FILE_PREFIX)).toList()) {
                try {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(f);
                    }
                } catch (IOException | RuntimeException ignored) {
                    /* best-effort */
                }
            }
        } catch (IOException ignored) {
            /* best-effort */
        }
    }
}
