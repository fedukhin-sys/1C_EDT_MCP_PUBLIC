package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.ipc.McpHome;

/**
 * Пакетный конфигуратор {@code 1cv8 DESIGNER} — для того, чего исполнитель EDT не делает: загрузки
 * {@code .cfe} именно расширением ({@code importCfToInfobase} EDT строит {@code /LoadCfg} без
 * {@code -Extension}) и проверки только что созданной серверной базы.
 *
 * <p><b>Аргументы — отдельными элементами команды</b>, как их собирает сама EDT
 * ({@code RuntimeExecutionCommandBuilder}: {@code "/N", "<имя>"}, {@code "/F", "<каталог>"}).
 * {@code ProcessBuilder} JDK 17 без SecurityManager (legacy-режим) берёт в кавычки элемент с пробелом
 * целиком и кавычки внутри не трогает: слитный {@code /N"Имя Фамилия"} дошёл бы до 1cv8 как
 * {@code "/N"Имя Фамилия""} и распался по пробелу. Проверено на 8.5.1.1423 (2026-09-27):
 * {@code /Out"<путь с пробелом>"} одним элементом 1cv8 не понял — лог не появился, двумя элементами
 * ({@code "/Out", "<путь с пробелом>"}) — понял.
 *
 * <p>Ожидание — порциями {@link #poll} с проверкой монитора: отмена задания (Progress view, лимит
 * времени задания) убивает процесс. {@code waitFor} с таймаутом — блокирующее ожидание (поток спит),
 * а не холостой цикл.
 */
public class DesignerBatch {

    /** Итог запуска: код выхода и текст лога {@code /Out} (если он пуст — stderr процесса). */
    public record Result(int exitCode, String output) {}

    static final Duration DEFAULT_POLL = Duration.ofSeconds(1);

    private final RuntimeCli.ProcessFactory processFactory;
    private final Duration poll;

    public DesignerBatch() {
        this(new RuntimeCli.DefaultProcessFactory(), DEFAULT_POLL);
    }

    /**
     * @param poll как часто, ожидая завершения 1cv8, проверять отмену задания; не меньше 1 мс
     */
    public DesignerBatch(RuntimeCli.ProcessFactory processFactory, Duration poll) {
        this.processFactory = Objects.requireNonNull(processFactory, "processFactory");
        this.poll = requireAtLeastOneMillisecond(poll, "poll");
    }

    /**
     * Аргументы подключения к базе: {@code /F <каталог>} для файловой, {@code /S <сервер>\<база>} для
     * серверной. Другие виды баз (веб-публикации) пакетный конфигуратор этим путём не открывает.
     */
    public static List<String> connection(InfobaseReference infobase) throws ToolException {
        IConnectionString cs = infobase.getConnectionString();
        if (cs instanceof FileConnectionString file && notBlank(file.getFile())) {
            return List.of("/F", file.getFile());
        }
        if (cs instanceof ServerConnectionString server && notBlank(server.getServer())
                && notBlank(server.getReference())) {
            return List.of("/S", server.getServer() + "\\" + server.getReference());
        }
        String connection = cs == null ? "не задана" : "'" + cs.asConnectionString() + "'";
        throw new ToolException("база " + infobase.getName() + ": пакетный конфигуратор открывает только файловые "
            + "и серверные базы, а строка подключения " + connection);
    }

    /** {@code /N <пользователь>} и, если пароль задан, {@code /P <пароль>} — как у EDT, отдельными элементами. */
    public static void appendCredentials(List<String> command, String user, String password) {
        if (user == null || user.isEmpty()) return;
        command.add("/N");
        command.add(user);
        if (password != null && !password.isEmpty()) {
            command.add("/P");
            command.add(password);
        }
    }

    /**
     * Запускает {@code <executable> DESIGNER <arguments> /Out <лог> /DisableStartupDialogs} и ждёт
     * завершения. Лог создаётся в каталоге {@code tmp} домашнего каталога сервера и удаляется после
     * чтения. Причину отказа 1cv8 пишет в {@code /Out}, а не в stderr; {@code /DisableStartupDialogs} —
     * чтобы процесс без окна не повис на модальном диалоге.
     *
     * @param monitor монитор задания: отменён — процесс убивается, бросается
     *     {@link OperationCanceledException}; {@code null} — без проверки отмены
     * @param timeout лимит ожидания; {@code null} — без своего лимита (его задаёт задание через монитор)
     * @return код выхода и текст лога; ненулевой код вызывающий превращает в ошибку сам — только он знает,
     *     какие секреты маскировать
     */
    public Result run(File executable, List<String> arguments, IProgressMonitor monitor, Duration timeout)
            throws ToolException {
        if (executable == null) {
            throw new ToolException("не найден исполняемый файл конфигуратора 1cv8");
        }
        if (monitor != null && monitor.isCanceled()) {
            throw new OperationCanceledException("задание отменено до запуска конфигуратора 1cv8");
        }
        Path outLog = newOutLog();
        try {
            List<String> command = new ArrayList<>();
            command.add(executable.getAbsolutePath());
            command.add("DESIGNER");
            command.addAll(arguments);
            command.add("/Out");
            command.add(outLog.toString());
            command.add("/DisableStartupDialogs");
            Process process;
            try {
                process = processFactory.start(command, null);
            } catch (IOException e) {
                throw new ToolException("не удалось запустить конфигуратор 1cv8: " + e.getMessage(), e);
            }
            // Потоки процесса дренируются параллельно с ожиданием: переполненный pipe подвешивает сам
            // процесс, и никакая отмена его уже не дождётся.
            StringBuilder stderr = new StringBuilder();
            Thread errDrain = drainAsync(process.getErrorStream(), stderr);
            Thread outDrain = drainAsync(process.getInputStream(), new StringBuilder());
            await(process, monitor, timeout);
            joinQuietly(errDrain);
            joinQuietly(outDrain);
            String output = OutLogReader.read(outLog);
            if (output.isEmpty()) {
                synchronized (stderr) {
                    output = stderr.toString().trim();
                }
            }
            return new Result(process.exitValue(), output);
        } finally {
            try {
                Files.deleteIfExists(outLog);
            } catch (IOException ignored) {
                // временный лог; не удалился — не беда
            }
        }
    }

    private void await(Process process, IProgressMonitor monitor, Duration timeout) throws ToolException {
        long started = System.nanoTime();
        try {
            while (!process.waitFor(poll.toMillis(), TimeUnit.MILLISECONDS)) {
                if (monitor != null && monitor.isCanceled()) {
                    process.destroyForcibly();
                    throw new OperationCanceledException("конфигуратор 1cv8 остановлен: задание отменено");
                }
                if (timeout != null && System.nanoTime() - started >= timeout.toNanos()) {
                    process.destroyForcibly();
                    throw new ToolException("конфигуратор 1cv8 не завершился за " + describe(timeout)
                        + " — процесс остановлен");
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new OperationCanceledException("ожидание конфигуратора 1cv8 прервано");
        }
    }

    private static String describe(Duration duration) {
        if (duration.toMinutes() >= 2) return duration.toMinutes() + " мин";
        if (duration.toSeconds() >= 1) return duration.toSeconds() + " с";
        return duration.toMillis() + " мс";
    }

    /** Файл для {@code /Out} — в каталоге tmp домашнего каталога сервера, а не в системном temp. */
    static Path newOutLog() throws ToolException {
        try {
            Path dir = McpHome.root().resolve("tmp");
            Files.createDirectories(dir);
            return Files.createTempFile(dir, "1cv8-out-", ".log");
        } catch (IOException | RuntimeException e) {
            throw new ToolException("не удалось создать файл лога 1cv8: " + e.getMessage(), e);
        }
    }

    /** Вычитывает поток процесса в daemon-потоке, чтобы не блокировать ожидание. */
    static Thread drainAsync(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            String read = tail(in);
            synchronized (sink) { sink.append(read); }
        }, "edt-mcp-1cv8-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void joinQuietly(Thread t) {
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Первые 4 КБ потока: дальше читать незачем, а удерживать процесс — нельзя. */
    static String tail(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1 && baos.size() < 4096) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "<failed to read stderr: " + e.getMessage() + ">";
        }
    }

    /**
     * Интервал ожидания: {@code null} и меньше 1 мс — отказ. {@code toMillis()} такого интервала равен 0,
     * а {@code tryLock(0)}/{@code waitFor(0)} в цикле — это уже холостой цикл.
     */
    static Duration requireAtLeastOneMillisecond(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least 1 ms, got " + value);
        }
        return value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
