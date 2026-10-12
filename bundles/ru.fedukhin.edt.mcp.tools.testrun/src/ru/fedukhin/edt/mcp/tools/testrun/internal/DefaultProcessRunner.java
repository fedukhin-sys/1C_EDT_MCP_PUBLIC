package ru.fedukhin.edt.mcp.tools.testrun.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher.ProcessRunner;

/**
 * Боевая реализация {@link ProcessRunner} поверх {@link ProcessBuilder}.
 *
 * <p>Таймаут здесь обязан быть настоящим. Прежняя версия читала stdout/stderr блокирующими
 * {@code readAllBytes()} и звала {@code waitFor()} без таймаута, полностью игнорируя
 * {@code timeoutSeconds}. Внешний {@code Future.cancel(true)} такое чтение не прерывает
 * (interrupt не снимает блокировку на pipe), поэтому 1cv8.exe продолжал жить: держал ИБ,
 * превращая следующий {@code deploy_project} в no-op, и навсегда занимал однопоточный
 * executor {@link TestRunnerLauncher} — все последующие прогоны тестов висли до рестарта EDT.
 *
 * <p>Поэтому: потоки процесса дренируются асинхронно (иначе переполненный pipe подвесит
 * сам процесс и {@code waitFor} с таймаутом не спасёт), ожидание — {@code waitFor(timeout)},
 * а по таймауту процесс принудительно уничтожается и результат честно помечается
 * {@link ProcessRunner.RunOutcome#killed()}.
 *
 * <p>Под наблюдателем ожидание идёт порциями, и между ними наблюдатель может остановить
 * процесс досрочно — так же принудительно, с его причиной в
 * {@link ProcessRunner.RunOutcome#stopReason()}.
 */
public class DefaultProcessRunner implements ProcessRunner {

    /** Сколько ждать фактической смерти процесса после destroyForcibly. */
    private static final long KILL_WAIT_SECONDS = 5;

    /** Сколько ждать до-чтения хвостов stdout/stderr после завершения процесса. */
    private static final long DRAIN_WAIT_SECONDS = 5;

    /** exitCode, отдаваемый вместо настоящего, когда процесс не завершился сам. */
    private static final int EXIT_CODE_KILLED = -1;

    /** Как часто спрашивать наблюдателя, пока процесс жив. */
    private static final long DEFAULT_WATCH_POLL_MILLIS = 1000;

    private final long watchPollMillis;

    public DefaultProcessRunner() {
        this(DEFAULT_WATCH_POLL_MILLIS);
    }

    /** Шов для тестов: период опроса наблюдателя. */
    public DefaultProcessRunner(long watchPollMillis) {
        this.watchPollMillis = watchPollMillis;
    }

    @Override
    public RunOutcome run(List<String> command, Map<String, String> env, int timeoutSeconds)
        throws Exception {
        return run(command, env, timeoutSeconds, null);
    }

    @Override
    public RunOutcome run(List<String> command, Map<String, String> env, int timeoutSeconds, Watch watch)
        throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }
        pb.redirectErrorStream(false);

        Process p = startProcess(pb);
        StreamDrainer stdout = StreamDrainer.start(p.getInputStream(), "edt-mcp-proc-stdout");
        StreamDrainer stderr = StreamDrainer.start(p.getErrorStream(), "edt-mcp-proc-stderr");

        Wait wait = watch == null ? waitPlain(p, timeoutSeconds) : waitWatched(p, timeoutSeconds, watch);
        boolean killed = false;
        if (!wait.exited()) {
            p.destroyForcibly();
            p.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
            killed = true;
        }
        return new RunOutcome(
            killed ? EXIT_CODE_KILLED : p.exitValue(),
            stdout.await(),
            stderr.await(),
            killed,
            wait.stopReason());
    }

    /** Seam для тестов: даёт перехватить запущенный процесс. */
    protected Process startProcess(ProcessBuilder pb) throws IOException {
        return pb.start();
    }

    /** Чем кончилось ожидание: процесс вышел сам, вышло время или наблюдатель велел остановить. */
    private record Wait(boolean exited, String stopReason) { }

    private static Wait waitPlain(Process p, int timeoutSeconds) throws InterruptedException {
        boolean exited = timeoutSeconds > 0
            ? p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            : waitForever(p);
        return new Wait(exited, null);
    }

    /**
     * Ожидание выхода порциями по {@code watchPollMillis} с вопросом наблюдателю между ними.
     * Каждая порция — блокирующий {@code waitFor} с пределом, а не холостой цикл: процессор
     * между проверками свободен, а вышедший процесс отпускает ожидание сразу.
     */
    private Wait waitWatched(Process p, int timeoutSeconds, Watch watch) throws InterruptedException {
        // Без таймаута — «бесконечность» в пределах, при которых наносекунды не переполняются.
        long limitNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds > 0 ? timeoutSeconds : Integer.MAX_VALUE);
        long deadline = System.nanoTime() + limitNanos;
        long sliceNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, watchPollMillis));
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return new Wait(false, null);
            }
            if (p.waitFor(Math.min(left, sliceNanos), TimeUnit.NANOSECONDS)) {
                return new Wait(true, null);
            }
            String reason = watch.stopReason();
            if (reason != null) {
                return new Wait(false, reason);
            }
        }
    }
    private static boolean waitForever(Process p) throws InterruptedException {
        p.waitFor();
        return true;
    }

    /**
     * Вычитывает поток процесса в отдельном daemon-потоке.
     *
     * <p>Daemon — чтобы намертво залипшее чтение (процесс убит, но дескриптор унаследован
     * внуком) не мешало остановке JVM/EDT.
     */
    private static final class StreamDrainer {

        private final Thread thread;
        private volatile String content = "";

        private StreamDrainer(InputStream in, String name) {
            this.thread = new Thread(() -> {
                try {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    // Штатно при destroyForcibly: pipe рвётся. Что успели прочитать — уже потеряно,
                    // но диагностику несут exit-код, флаг killed и /Out-лог 1cv8.
                    content = "";
                }
            }, name);
            this.thread.setDaemon(true);
        }

        static StreamDrainer start(InputStream in, String name) {
            StreamDrainer d = new StreamDrainer(in, name);
            d.thread.start();
            return d;
        }

        /** Ждёт конца чтения ограниченное время и отдаёт прочитанное. */
        String await() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(DRAIN_WAIT_SECONDS));
            return content;
        }
    }
}
