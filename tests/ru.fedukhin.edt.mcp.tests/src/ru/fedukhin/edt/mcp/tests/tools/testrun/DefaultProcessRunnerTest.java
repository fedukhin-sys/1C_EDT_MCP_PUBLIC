package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.testrun.internal.DefaultProcessRunner;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher.ProcessRunner.RunOutcome;

/**
 * Таймаут раннера обязан реально убивать процесс.
 *
 * <p>Исторический баг: {@code timeoutSeconds} игнорировался — блокирующие {@code readAllBytes()} и
 * {@code waitFor()} без таймаута держали поток до самостоятельного завершения 1cv8. Внешний
 * {@code Future.cancel(true)} такое чтение не прерывает, поэтому 1cv8 продолжал жить, держал ИБ
 * (следующий {@code deploy_project} становился no-op) и навсегда занимал однопоточный executor.
 */
public class DefaultProcessRunnerTest {

    /** Даёт тесту доступ к запущенному {@link Process} — иначе живость проверить нечем. */
    private static final class SpyingRunner extends DefaultProcessRunner {

        private volatile Process started;

        @Override
        protected Process startProcess(ProcessBuilder pb) throws java.io.IOException {
            Process p = super.startProcess(pb);
            started = p;
            return p;
        }
    }

    private ExecutorService executor;

    @After
    public void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Процесс, живущий заведомо дольше любого таймаута теста. */
    private static List<String> longRunningCommand() {
        return List.of("cmd", "/c", "ping", "-n", "30", "127.0.0.1");
    }

    @Test
    public void timeout_killsProcess_andReportsKilled() throws Exception {
        SpyingRunner runner = new SpyingRunner();

        long startedAt = System.nanoTime();
        RunOutcome outcome = runner.run(longRunningCommand(), Map.of(), 2);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue("раннер обязан вернуться сразу после таймаута, а не ждать конца процесса; ждал "
            + elapsedMs + " мс", elapsedMs < 15_000);
        assertTrue("таймаут обязан быть отражён в результате честным флагом", outcome.killed());
        assertNotNull(runner.started);
        assertFalse("процесс обязан быть убит, а не оставлен жить и держать ИБ",
            runner.started.isAlive());
    }

    @Test
    public void normalCompletion_isNotReportedAsKilled() throws Exception {
        DefaultProcessRunner runner = new DefaultProcessRunner();

        RunOutcome outcome = runner.run(List.of("cmd", "/c", "echo", "hi"), Map.of(), 30);

        assertEquals(0, outcome.exitCode());
        assertFalse(outcome.killed());
        assertTrue("stdout обязан дренироваться: " + outcome.stdout(), outcome.stdout().contains("hi"));
    }

    /**
     * Ключевое следствие бага: однопоточный executor {@code TestRunnerLauncher} — {@code @Singleton},
     * поэтому один зависший прогон делал недоступными все последующие {@code run_tests} до рестарта EDT.
     */
    @Test
    public void timedOutRun_doesNotWedgeSingleThreadExecutor() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        DefaultProcessRunner runner = new DefaultProcessRunner();

        Future<RunOutcome> first = executor.submit(() -> runner.run(longRunningCommand(), Map.of(), 2));
        Future<RunOutcome> second = executor.submit(() -> runner.run(List.of("cmd", "/c", "echo", "ok"), Map.of(), 30));

        assertTrue(first.get(20, TimeUnit.SECONDS).killed());
        RunOutcome secondOutcome = second.get(20, TimeUnit.SECONDS);
        assertEquals("после таймаута executor обязан оставаться рабочим", 0, secondOutcome.exitCode());
        assertFalse(secondOutcome.killed());
    }
}
