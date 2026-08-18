package ru.fedukhin.edt.mcp.core.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Реестр живых инстанций MCP-сервера: каждая публикует файл со своим портом,
 * рабочей областью и списком проектов, а клиент по нему выбирает, к какому
 * порту обращаться.
 *
 * <p><b>Раскладка файла.</b> Байт 0 — только замок присутствия, полезные данные
 * начинаются со смещения 1. Так сделано потому, что блокировка на Windows
 * мандатная: залоченный диапазон нельзя прочитать из другого процесса, а JSON
 * нужен именно чужому процессу.
 *
 * <p><b>Определение живости.</b> Замок держится всё время жизни процесса, и ОС
 * снимает его при крахе. Поэтому «удалось взять байт 0» означает «владельца нет,
 * запись протухла» — эвристики по PID не нужны.
 */
public final class InstanceBeacon implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DATA_OFFSET = 1;

    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceBeacon(Path file, FileChannel channel, FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    public static InstanceBeacon publish(InstanceRecord rec) throws IOException {
        Files.createDirectories(McpHome.instances());
        Path file = McpHome.instances().resolve(rec.pid() + ".json");
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock fl = ch.tryLock(0, 1, false);
            if (fl == null) {
                throw new IOException("маячок " + file + " уже занят другим процессом");
            }
            byte[] json = MAPPER.writeValueAsBytes(rec);
            ch.truncate(DATA_OFFSET);
            ch.write(ByteBuffer.wrap(json), DATA_OFFSET);
            ch.force(true);
            return new InstanceBeacon(file, ch, fl);
        } catch (IOException | RuntimeException e) {
            try { ch.close(); } catch (IOException ignored) { /* уже падаем */ }
            throw e;
        }
    }

    /** Живые инстанции; осиротевшие файлы подметает попутно. */
    public static List<InstanceRecord> readAll() {
        List<InstanceRecord> out = new ArrayList<>();
        Path dir = McpHome.instances();
        if (!Files.isDirectory(dir)) return out;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        } catch (IOException e) {
            return out;
        }
        for (Path f : files) {
            if (isOrphan(f)) {
                try { Files.deleteIfExists(f); } catch (IOException ignored) { /* best-effort */ }
                continue;
            }
            InstanceRecord rec = readRecord(f);
            if (rec != null) out.add(rec);
        }
        return out;
    }

    /**
     * Замок взялся — владельца нет. {@link OverlappingFileLockException} означает
     * обратное: файл держит наша же JVM, то есть это наш собственный маячок.
     */
    private static boolean isOrphan(Path f) {
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock probe = ch.tryLock(0, 1, false);
            if (probe == null) return false;
            probe.release();
            return true;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Читает JSON со смещения 1. Байт 0 намеренно не трогаем: у живой чужой
     * инстанции он залочен, и чтение всего файла целиком упало бы IOException.
     */
    private static InstanceRecord readRecord(Path f) {
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size <= DATA_OFFSET) return null;
            ByteBuffer buf = ByteBuffer.allocate((int) (size - DATA_OFFSET));
            ch.read(buf, DATA_OFFSET);
            String json = new String(buf.array(), StandardCharsets.UTF_8);
            return MAPPER.readValue(json, InstanceRecord.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override public void close() {
        try {
            if (lock != null && lock.isValid()) lock.release();
        } catch (IOException ignored) { /* best-effort */ }
        try {
            channel.close();
        } catch (IOException ignored) { /* best-effort */ }
        // Удалять только после закрытия канала: на Windows открытый handle не даст.
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) { /* best-effort */ }
    }
}
