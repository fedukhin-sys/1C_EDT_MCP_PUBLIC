package ru.fedukhin.edt.mcp.core.ipc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Межпроцессный замок поверх {@link FileChannel#tryLock}.
 *
 * <p><b>Почему файловый замок.</b> Он не тянет зависимостей (именованный мьютекс
 * Windows потребовал бы JNA, что запрещено правилами target platform), а на
 * Windows блокировка мандатная и снимается ОС при крахе процесса — стухших
 * замков не бывает, в отличие от схемы «файл-флаг плюс PID».
 *
 * <p><b>Раскладка файла.</b> Байт 0 — сам замок, со смещения 1 — описание
 * держателя. Разделение обязательно: залоченный диапазон нельзя прочитать из
 * другого процесса, а текст держателя нужен именно чужому процессу, чтобы
 * сформулировать внятный отказ.
 *
 * <p><b>Внутрипроцессный слой.</b> {@link FileLock} эксклюзивен на уровне JVM:
 * второй {@code tryLock} того же файла из того же процесса бросает
 * {@link OverlappingFileLockException}. Поэтому перед файловым замком стоит
 * {@link Semaphore} — именно семафор, а не {@code ReentrantLock}: повторный
 * захват тем же потоком должен получать отказ «занято», а не проходить насквозь.
 */
public final class InterProcessLock implements AutoCloseable {

    private static final int DATA_OFFSET = 1;
    private static final long POLL_MS = 200L;

    private static final ConcurrentHashMap<String, Semaphore> LOCAL = new ConcurrentHashMap<>();

    private final Semaphore local;
    private final FileChannel channel;
    private final FileLock fileLock;

    private InterProcessLock(Semaphore local, FileChannel channel, FileLock fileLock) {
        this.local = local;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    /**
     * @param key логический ресурс, например {@code "ib:uuid:4cf26896"}
     * @param holderInfo кто и зачем берёт замок — попадёт в текст чужого отказа
     * @param wait сколько ждать освобождения
     * @throws LockTimeoutException ресурс занят дольше {@code wait}
     */
    public static InterProcessLock acquire(String key, String holderInfo, Duration wait)
            throws LockTimeoutException, IOException {
        Semaphore local = LOCAL.computeIfAbsent(key, k -> new Semaphore(1, true));
        boolean localHeld;
        try {
            localHeld = local.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockTimeoutException("ожидание замка '" + key + "' прервано");
        }
        if (!localHeld) {
            throw new LockTimeoutException(busyText(key, holderInfo));
        }
        FileChannel ch = null;
        try {
            Path file = fileFor(key);
            Files.createDirectories(file.getParent());
            ch = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock fl = awaitFileLock(ch, key, holderInfo, wait);
            writeHolder(ch, holderInfo);
            return new InterProcessLock(local, ch, fl);
        } catch (IOException | LockTimeoutException | RuntimeException e) {
            if (ch != null) {
                try { ch.close(); } catch (IOException ignored) { /* уже падаем */ }
            }
            local.release();
            throw e;
        }
    }

    private static FileLock awaitFileLock(FileChannel ch, String key, String holderInfo, Duration wait)
            throws LockTimeoutException, IOException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            FileLock fl;
            try {
                fl = ch.tryLock(0, 1, false);
            } catch (OverlappingFileLockException e) {
                // Тот же файл уже держит наша JVM по другому ключу-синониму —
                // для вызывающего это неотличимо от «занято».
                throw new LockTimeoutException(busyText(key, holderInfo));
            }
            if (fl != null) return fl;
            if (System.nanoTime() >= deadline) {
                throw new LockTimeoutException(busyText(key, holderInfo));
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockTimeoutException("ожидание замка '" + key + "' прервано");
            }
        }
    }

    /**
     * Кто держит замок сейчас. Читает только со смещения 1 — байт 0 у живого
     * держателя залочен, и чтение файла целиком упало бы.
     */
    public static Optional<String> holderOf(String key) {
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size <= DATA_OFFSET) return Optional.empty();
            ByteBuffer buf = ByteBuffer.allocate((int) (size - DATA_OFFSET));
            ch.read(buf, DATA_OFFSET);
            String text = new String(buf.array(), StandardCharsets.UTF_8);
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String busyText(String key, String requester) {
        String holder = holderOf(key).orElse("(держатель неизвестен)");
        return "ресурс '" + key + "' занят: " + holder + "; запрошено: " + requester;
    }

    private static void writeHolder(FileChannel ch, String holderInfo) throws IOException {
        byte[] data = holderInfo.getBytes(StandardCharsets.UTF_8);
        ch.truncate(DATA_OFFSET);
        ch.write(ByteBuffer.wrap(data), DATA_OFFSET);
        ch.force(true);
    }

    private static Path fileFor(String key) {
        return McpHome.locks().resolve("l-" + sha1(key) + ".lock");
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 недоступен", e);
        }
    }

    @Override public void close() {
        try {
            if (fileLock != null && fileLock.isValid()) fileLock.release();
        } catch (IOException ignored) { /* best-effort */ }
        try {
            channel.close();
        } catch (IOException ignored) { /* best-effort */ }
        local.release();
    }
}
