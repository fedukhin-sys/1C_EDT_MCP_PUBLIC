package ru.fedukhin.edt.mcp.tools.testrun.internal;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Launches 1cv8.exe ENTERPRISE with the encoded selector, waits for the result
 * file to be produced by the BSL handler, parses it into {@link TestRunResult}.
 * Owns a single-thread daemon executor (Stage 7d pattern).
 *
 * <p>Процесс идёт под наблюдателем ({@code RunWatch}) — ждать его выхода бывает незачем:
 * <ul>
 *   <li>результат уже записан, а клиент не закрывается (тест оставил модальное окно —
 *       {@code ПоказатьПредупреждение}, вопрос): через {@link #DEFAULT_RESULT_GRACE} процесс
 *       останавливается, результат отдаётся с предупреждением;</li>
 *   <li>в журнале регистрации — необработанная ошибка сеанса прогона ({@link FailureProbe}):
 *       обычно модуль, который EDT считает правильным, не компилируется в рантайме; окно ошибки
 *       стоит, обработчик раннера не выполняется. Если за {@link #DEFAULT_ERROR_GRACE}
 *       результат так и не появился, процесс останавливается, ответ — текст ошибки.</li>
 * </ul>
 * Без этого {@code run_test_method} в обоих случаях ждал до таймаута (10–12.10.2026: 900
 * и 1500 с впустую) и отвечал «timeout», даже когда результат лежал на диске.
 */
@Singleton
public class TestRunnerLauncher {

    public static final int MIN_TIMEOUT_SECONDS = 30;
    public static final int MAX_TIMEOUT_SECONDS = 3600;
    private static final long SHUTDOWN_WAIT_SEC = 5;

    /**
     * Запас поверх {@code timeoutSeconds} для внешнего ожидания: раннеру нужно время убить
     * процесс (destroyForcibly + подтверждение) и дочитать хвосты потоков.
     */
    private static final int WATCHDOG_GRACE_SECONDS = 15;

    /**
     * Сколько клиенту дать закрыться самому после записи результата. Обычно он выходит за
     * секунды ({@code ЗавершитьРаботуСистемы(Ложь)}); запас большой, потому что убитый клиент
     * оставляет сеанс на сервере 1С, а тот держит лицензию до засыпания (~20 минут).
     */
    public static final Duration DEFAULT_RESULT_GRACE = Duration.ofSeconds(30);

    /** Сколько после ошибки в журнале ещё ждать результата, прежде чем остановить прогон. */
    public static final Duration DEFAULT_ERROR_GRACE = Duration.ofSeconds(30);

    /** Как часто читать журнал регистрации: чтение — разбор дописанного хвоста партиции. */
    public static final Duration DEFAULT_PROBE_INTERVAL = Duration.ofSeconds(10);

    /** {@link ProcessRunner.RunOutcome#stopReason()}: результат записан, клиент не закрылся. */
    public static final String STOP_RESULT_WRITTEN = "result-written";

    /** {@link ProcessRunner.RunOutcome#stopReason()}: необработанная ошибка в сеансе прогона. */
    public static final String STOP_UNHANDLED_ERROR = "unhandled-error";

    /** Abstracted process launcher — real impl uses ProcessBuilder; tests inject a fake. */
    public interface ProcessRunner {
        /**
         * @param killed     процесс не завершился сам и был уничтожен — по таймауту или по просьбе
         *                   наблюдателя; {@code exitCode} в этом случае синтетический и о работе
         *                   процесса ничего не говорит
         * @param stopReason что ответил наблюдатель, остановивший процесс, или {@code null}
         *                   (вышел сам или убит по таймауту)
         */
        record RunOutcome(int exitCode, String stdout, String stderr, boolean killed, String stopReason) {
            public RunOutcome(int exitCode, String stdout, String stderr, boolean killed) {
                this(exitCode, stdout, stderr, killed, null);
            }
            /** Штатное завершение процесса (без таймаута). */
            public RunOutcome(int exitCode, String stdout, String stderr) {
                this(exitCode, stdout, stderr, false);
            }
        }

        /** Наблюдатель за живым процессом: раннер спрашивает его между ожиданиями выхода. */
        @FunctionalInterface
        interface Watch {
            /** @return причина остановить процесс сейчас или {@code null} — ждать дальше */
            String stopReason();
        }

        /**
         * @param timeoutSeconds по истечении — процесс обязан быть уничтожен, а результат помечен
         *                       {@link RunOutcome#killed()}; значение {@code <= 0} снимает таймаут
         */
        RunOutcome run(List<String> command, Map<String, String> env, int timeoutSeconds) throws Exception;

        /**
         * То же под наблюдателем: если {@code watch} вернул причину, процесс уничтожается, а
         * результат помечается {@code killed} с этой причиной. Реализация по умолчанию
         * наблюдателя не спрашивает — ждёт выхода или таймаута.
         */
        default RunOutcome run(List<String> command, Map<String, String> env, int timeoutSeconds,
                               Watch watch) throws Exception {
            return run(command, env, timeoutSeconds);
        }
    }

    /** Источник сведений о необработанной ошибке в сеансе прогона (журнал регистрации). */
    @FunctionalInterface
    public interface FailureProbe {
        /**
         * @return описание ошибки или {@code null}, если ошибки нет; исключение — источник
         *         недоступен, прогон идёт как без него
         */
        String unhandledError() throws Exception;
    }

    private final ProcessRunner runner;
    private final long resultGraceMillis;
    private final long errorGraceMillis;
    private final long probeIntervalMillis;
    private volatile ExecutorService executor;

    @Inject
    public TestRunnerLauncher(ProcessRunner runner) {
        this(runner, DEFAULT_RESULT_GRACE, DEFAULT_ERROR_GRACE, DEFAULT_PROBE_INTERVAL);
    }

    /** Шов для тестов: сроки наблюдателя. */
    public TestRunnerLauncher(ProcessRunner runner, Duration resultGrace, Duration errorGrace,
                              Duration probeInterval) {
        this.runner = runner;
        this.resultGraceMillis = resultGrace.toMillis();
        this.errorGraceMillis = errorGrace.toMillis();
        this.probeIntervalMillis = probeInterval.toMillis();
    }

    /** Запуск без явных учётных данных — ИБ должна пускать по OS-аутентификации. */
    public TestRunResult runWithTimeout(String exe1cv8, String ibConnection,
                                         String startupParam, int timeoutSeconds) throws ToolException {
        return runWithTimeout(exe1cv8, ibConnection, startupParam, timeoutSeconds, null, null);
    }

    /**
     * @param user     имя пользователя ИБ (может быть {@code null}/пусто — тогда {@code /N} не
     *                 передаётся и 1cv8 идёт по OS-аутентификации)
     * @param password пароль; попадает только в командную строку 1cv8 и НИКОГДА — в текст
     *                 ошибок и логи (см. {@link #maskSecrets})
     */
    public TestRunResult runWithTimeout(String exe1cv8, String ibConnection,
                                         String startupParam, int timeoutSeconds,
                                         String user, String password) throws ToolException {
        return runWithTimeout(exe1cv8, ibConnection, startupParam, timeoutSeconds, user, password, null);
    }

    /**
     * @param probe журнал регистрации базы для досрочной остановки по необработанной ошибке;
     *              {@code null} — только по результату и таймауту
     */
    public TestRunResult runWithTimeout(String exe1cv8, String ibConnection,
                                         String startupParam, int timeoutSeconds,
                                         String user, String password,
                                         FailureProbe probe) throws ToolException {
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new ToolException("timeoutSeconds must be in ["
                + MIN_TIMEOUT_SECONDS + ", " + MAX_TIMEOUT_SECONDS + "], got " + timeoutSeconds);
        }
        Path resultFile = resolveResultFile(startupParam);
        Path outLog = resultFile.resolveSibling(resultFile.getFileName().toString().replace(".json", ".1cv8.log"));
        List<String> cmd = buildCommand(exe1cv8, ibConnection, startupParam, outLog, user, password);
        RunWatch watch = new RunWatch(resultFile, probe);
        ExecutorService exec = ensureExecutor();
        Future<ProcessRunner.RunOutcome> f = exec.submit(() -> runner.run(cmd, Map.of(), timeoutSeconds, watch));
        ProcessRunner.RunOutcome outcome;
        try {
            // Таймаут отрабатывает сам раннер (он убивает процесс), поэтому внешнее ожидание —
            // с запасом: иначе оно сработает первым и мы потеряем честный результат с killed.
            outcome = f.get(timeoutSeconds + WATCHDOG_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Сюда попадаем, только если раннер не уложился даже в запас, т.е. поток занят
            // неизвестно чем. Отменять мало: cancel(true) не прерывает блокирующий ввод-вывод,
            // а executor однопоточный — не пересоздав его, мы подвесим все следующие прогоны.
            f.cancel(true);
            discardExecutor();
            throw new ToolException("test run timeout after " + timeoutSeconds
                + "s; раннер не отозвался и за отведённый запас — процесс 1cv8 мог остаться живым"
                + " и держать ИБ, проверьте диспетчер задач;"
                + " partial results may have been written but are not loaded");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new ToolException("test run failed: "
                + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw new ToolException("test run interrupted");
        }
        if (outcome.killed()) {
            // Результат мог успеть записаться: клиент держало окно теста, или таймаут пришёлся
            // на его закрытие. Тесты тогда выполнены — отдаём их, а не «timeout».
            TestRunResult written = readResult(resultFile);
            if (written != null) {
                return written.withWarning(STOP_RESULT_WRITTEN.equals(outcome.stopReason())
                    ? "клиент 1С не закрылся сам за " + resultGraceMillis / 1000 + " с после записи"
                        + " результата (обычно его держит модальное окно теста — ПоказатьПредупреждение,"
                        + " вопрос) и завершён принудительно; сеанс на сервере 1С живёт до засыпания"
                        + " и держит лицензию"
                    : "результат записан, но клиент 1С не закрылся и завершён принудительно"
                        + " по таймауту " + timeoutSeconds + " с");
            }
            if (STOP_UNHANDLED_ERROR.equals(outcome.stopReason())) {
                throw new ToolException("test run failed: сеанс прогона показал необработанную ошибку"
                    + " (журнал регистрации, событие _$PerformError$_): " + watch.error()
                    + "; результата нет — раннер до тестов не дошёл или тест прерван ошибкой,"
                    + " процесс 1cv8 остановлен. Частая причина — модуль не компилируется в рантайме"
                    + " (EDT такие ошибки не показывает); строка и позиция — в тексте ошибки");
            }
            String fromLog = watch.error() != null ? watch.error() : probeQuietly(probe);
            throw new ToolException("test run timeout after " + timeoutSeconds
                + "s; процесс 1cv8 принудительно завершён;"
                + (fromLog == null ? "" : " журнал регистрации сеанса прогона: " + fromLog + ";")
                + " partial results may have been written but are not loaded");
        }
        if (!Files.exists(resultFile)) {
            String stderr = outcome.stderr() == null ? "" : outcome.stderr();
            String stderrTail = stderr.length() > 500 ? stderr.substring(stderr.length() - 500) : stderr;
            String stdout = outcome.stdout() == null ? "" : outcome.stdout();
            String stdoutTail = stdout.length() > 500 ? stdout.substring(stdout.length() - 500) : stdout;
            String outLogContent = "";
            try {
                if (Files.exists(outLog)) {
                    String log = Files.readString(outLog);
                    outLogContent = log.length() > 1000 ? log.substring(log.length() - 1000) : log;
                }
            } catch (IOException ignored) { /* best effort */ }
            // Толстый клиент с ошибкой компиляции на старте выходит сам (exit=1), причина — в журнале.
            String fromLog = watch.error() != null ? watch.error() : probeQuietly(probe);
            throw new ToolException("test run failed: no result file produced "
                + "(exit=" + outcome.exitCode()
                + ", cmd=" + maskSecrets(cmd)
                + ", stderr tail: " + stderrTail
                + ", stdout tail: " + stdoutTail
                + ", 1cv8 /Out tail: " + outLogContent
                + (fromLog == null ? "" : ", журнал регистрации сеанса прогона: " + fromLog) + ")");
        }
        String json;
        try {
            json = Files.readString(resultFile);
        } catch (IOException e) {
            throw new ToolException("test run result file unreadable: " + e.getMessage(), e);
        }
        return TestResultParser.parse(json);
    }

    /**
     * Наблюдатель прогона: между ожиданиями выхода процесса проверяет файл результата и (не чаще
     * раза в {@code probeIntervalMillis}) журнал регистрации. Вызывается из одного потока раннера.
     */
    private final class RunWatch implements ProcessRunner.Watch {

        private final Path resultFile;
        private final FailureProbe probe;
        private long resultSeenAt = -1;
        private long lastProbeAt = -1;
        private long errorSeenAt = -1;
        private volatile String error;

        RunWatch(Path resultFile, FailureProbe probe) {
            this.resultFile = resultFile;
            this.probe = probe;
        }

        @Override
        public String stopReason() {
            long now = System.currentTimeMillis();
            if (readResult(resultFile) != null) {
                if (resultSeenAt < 0) resultSeenAt = now;
                return now - resultSeenAt >= resultGraceMillis ? STOP_RESULT_WRITTEN : null;
            }
            if (probe != null && error == null
                && (lastProbeAt < 0 || now - lastProbeAt >= probeIntervalMillis)) {
                lastProbeAt = now;
                error = probeQuietly(probe);
                if (error != null) errorSeenAt = now;
            }
            if (error != null && now - errorSeenAt >= errorGraceMillis) {
                return STOP_UNHANDLED_ERROR;
            }
            return null;
        }

        String error() {
            return error;
        }
    }

    /** Ошибка из журнала или {@code null}; недоступный журнал прогон не роняет. */
    private static String probeQuietly(FailureProbe probe) {
        if (probe == null) return null;
        try {
            return probe.unhandledError();
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** Разобранный файл результата или {@code null}: файла нет или он ещё дописывается. */
    private static TestRunResult readResult(Path resultFile) {
        if (!Files.isRegularFile(resultFile)) return null;
        try {
            return TestResultParser.parse(Files.readString(resultFile));
        } catch (IOException | ToolException | RuntimeException e) {
            return null;
        }
    }

    public synchronized void shutdown() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_WAIT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    /**
     * Бросает залипший executor: поток мог остаться занят навсегда, а executor однопоточный —
     * следующий {@code ensureExecutor} создаст свежий, и {@code run_tests} снова заработает
     * без рестарта EDT.
     */
    private synchronized void discardExecutor() {
        if (executor == null) return;
        executor.shutdownNow();
        executor = null;
    }

    private synchronized ExecutorService ensureExecutor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "edt-mcp-test-runner");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    /**
     * Маскирует значение {@code /P} в командной строке для вывода в сообщениях об ошибках.
     * Команда печатается целиком при провале запуска — пароль в логи и в ответ клиенту
     * (то есть в LLM/облако) попадать не должен.
     */
    static List<String> maskSecrets(List<String> cmd) {
        List<String> out = new ArrayList<>(cmd);
        int p = out.indexOf("/P");
        if (p >= 0 && p + 1 < out.size()) {
            out.set(p + 1, "***");
        }
        return out;
    }

    private static List<String> buildCommand(String exe1cv8, String ibConnection, String startupParam,
                                             Path outLog, String user, String password) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe1cv8);
        cmd.add("ENTERPRISE");
        // Java ProcessBuilder on Windows escapes embedded " as \", which 1cv8.exe does NOT
        // recognize in /IBConnectionString File="..." form — 1cv8 returns "Неопределена
        // информационная база". Parse the connection string into /F<path> or /S<srvr>\<ref>
        // shortcuts, which take simple unquoted values.
        appendIbArgs(cmd, ibConnection);
        // Без /N 1cv8 идёт по OS-аутентификации; если ИБ её не пускает, платформа показывает
        // диалог логина, а в headless-запуске это выглядит как зависание до таймаута.
        if (user != null && !user.isEmpty()) {
            cmd.add("/N");
            cmd.add(user);
            if (password != null) {
                cmd.add("/P");
                cmd.add(password);
            }
        }
        cmd.add("/C");
        cmd.add(startupParam);
        cmd.add("/DisableStartupMessages");
        cmd.add("/DisableStartupDialogs");
        cmd.add("/IBCheckAndRepair-");
        if (outLog != null) {
            cmd.add("/Out");
            cmd.add(outLog.toString());
        }
        return cmd;
    }

    /**
     * Translates a 1С connection string (forms {@code File="<path>";} or
     * {@code Srvr="<server>";Ref="<db>";}) into the {@code /F} / {@code /S}
     * shortcut command-line arguments that don't require embedded quote
     * escaping. Falls back to {@code /IBConnectionString} for unrecognised
     * forms, so the call still launches even if a new connection-string flavour
     * appears.
     */
    public static void appendIbArgs(List<String> cmd, String ibConnection) {
        String file = extractValue(ibConnection, "File");
        if (file != null) {
            cmd.add("/F" + file);
            return;
        }
        String server = extractValue(ibConnection, "Srvr");
        String ref = extractValue(ibConnection, "Ref");
        if (server != null && ref != null) {
            cmd.add("/S" + server + "\\" + ref);
            return;
        }
        cmd.add("/IBConnectionString");
        cmd.add(ibConnection);
    }

    /** Extracts the value of {@code key="value"} (or unquoted {@code key=value;}) from a 1С connection string. */
    private static String extractValue(String connection, String key) {
        if (connection == null) return null;
        int kIdx = connection.indexOf(key + "=");
        if (kIdx < 0) return null;
        int start = kIdx + key.length() + 1;
        if (start >= connection.length()) return null;
        if (connection.charAt(start) == '"') {
            int end = connection.indexOf('"', start + 1);
            if (end < 0) return null;
            return connection.substring(start + 1, end);
        }
        int end = connection.indexOf(';', start);
        if (end < 0) end = connection.length();
        return connection.substring(start, end);
    }

    private static Path resolveResultFile(String startupParam) throws ToolException {
        int idx = startupParam.indexOf("rf=");
        if (idx < 0) throw new ToolException("startup param has no rf= component");
        String tail = startupParam.substring(idx + 3);
        int semi = tail.indexOf(';');
        if (semi > 0) tail = tail.substring(0, semi);
        try {
            String path = new String(Base64.getDecoder().decode(tail), StandardCharsets.UTF_8);
            return Path.of(path);
        } catch (IllegalArgumentException e) {
            throw new ToolException("startup param rf= is not valid base64", e);
        }
    }

    /** Allocates a unique result-file path under the system temp dir. */
    public static Path allocateResultFile() throws ToolException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "edt-mcp-test-results");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ToolException("cannot create result temp dir: " + e.getMessage(), e);
        }
        purgeStaleResults(dir);
        return dir.resolve(UUID.randomUUID() + ".json");
    }

    /** Через сколько результат прошлого прогона считается мусором. */
    private static final Duration RESULT_TTL = Duration.ofDays(7);

    /**
     * Убирает результаты прошлых прогонов: каталог рос без ограничений — каждый run_tests
     * оставляет .json и .1cv8.log, и никто их не удалял.
     *
     * <p>Порог намеренно большой: файл свежего прогона может принадлежать параллельно
     * работающему 1cv8, и удалять его нельзя. Чистка — best-effort: не смогли удалить
     * (файл занят) — молча пропускаем, ронять из-за уборки мусора нечего.
     */
    private static void purgeStaleResults(Path dir) {
        long cutoff = System.currentTimeMillis() - RESULT_TTL.toMillis();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException | RuntimeException ignored) { /* best-effort */ }
            });
        } catch (IOException ignored) { /* best-effort */ }
    }
}
