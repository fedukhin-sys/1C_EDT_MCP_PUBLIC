package ru.fedukhin.edt.mcp.core.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.ipc.InterProcessLock;
import ru.fedukhin.edt.mcp.core.ipc.LockTimeoutException;
import ru.fedukhin.edt.mcp.core.ipc.McpHome;

/**
 * Флаг «в базе есть реальные ПДн», общий для всех инстанций 1C:EDT одного
 * пользователя.
 *
 * <p>Раньше флаг жил только в памяти процесса, поэтому
 * {@code set_infobase_pii_flag} действовал в одной сессии из N, а в остальных
 * оставался дефолт. Дефолт — {@code true} (fail-closed): пока явно не сказано
 * обратное, обезличиваем.
 *
 * <p>Битый файл трактуется как «флагов нет» — то есть в сторону усиления защиты,
 * а не ослабления.
 */
public final class PersistentFlagStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration LOCK_WAIT = Duration.ofSeconds(5);

    private static Path file() {
        return McpHome.privacy().resolve("infobase-flags.json");
    }

    public boolean containsRealPersonalData(String infobaseKey) {
        if (infobaseKey == null) return true;
        return read().getOrDefault(infobaseKey, Boolean.TRUE);
    }

    public void setFlag(String infobaseKey, boolean value) {
        if (infobaseKey == null) return;
        String holder = "privacy-flags pid=" + ProcessHandle.current().pid();
        try (InterProcessLock lock = InterProcessLock.acquire("privacy-flags", holder, LOCK_WAIT)) {
            Map<String, Boolean> flags = read();
            flags.put(infobaseKey, value);
            write(flags);
        } catch (LockTimeoutException | IOException e) {
            throw new IllegalStateException("не удалось сохранить флаг приватности: " + e.getMessage(), e);
        }
    }

    /**
     * Читает через {@code Class}, а не {@code TypeReference}: анонимный подкласс
     * {@code TypeReference} в OSGi упирается в loader constraint violation —
     * вендоренный в core Jackson и Jackson соседнего бандла грузятся разными
     * загрузчиками. Значения приводим руками, поэтому дженерик тут и не нужен.
     */
    private static Map<String, Boolean> read() {
        Path f = file();
        Map<String, Boolean> out = new HashMap<>();
        if (!Files.isRegularFile(f)) return out;
        try {
            Map<?, ?> raw = MAPPER.readValue(Files.readAllBytes(f), HashMap.class);
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() instanceof String key && e.getValue() instanceof Boolean value) {
                    out.put(key, value);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            // Битый файл не должен ослаблять защиту: «флагов нет» → fail-closed.
            return new HashMap<>();
        }
    }

    private static void write(Map<String, Boolean> flags) throws IOException {
        Path f = file();
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, MAPPER.writeValueAsBytes(flags));
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
