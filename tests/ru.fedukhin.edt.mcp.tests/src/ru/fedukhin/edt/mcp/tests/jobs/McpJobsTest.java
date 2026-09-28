package ru.fedukhin.edt.mcp.tests.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.jobs.JobLauncher;
import ru.fedukhin.edt.mcp.core.jobs.JobStatus;
import ru.fedukhin.edt.mcp.core.jobs.JobStep;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.core.jobs.StepStatus;

public class McpJobsTest {

    /** Монитор, который сигналит об отмене защёлкой — тело ждёт отмену без опроса. */
    static final class LatchMonitor extends NullProgressMonitor {
        final CountDownLatch canceled = new CountDownLatch(1);

        @Override public void setCanceled(boolean value) {
            super.setCanceled(value);
            if (value) canceled.countDown();
        }
    }

    /** Асинхронный запуск в отдельном потоке с заданным монитором. */
    static JobLauncher threadLauncher(LatchMonitor monitor) {
        return (title, work, notStarted) -> {
            Thread t = new Thread(() -> work.accept(monitor), "test-job");
            t.setDaemon(true);
            t.start();
            return () -> { };
        };
    }

    /**
     * Как Eclipse с задачей, стоящей в очереди Progress view: {@code work} не вызывается вовсе,
     * пока тест не решит её отменить. И возвращённая отмена, и вызов захваченного вручную
     * {@code notStarted} моделируют одно и то же: JobManager снимает ещё не начатое задание.
     */
    static final class QueuedLauncher implements JobLauncher {
        volatile Runnable notStarted;

        @Override
        public Runnable launch(String title, Consumer<IProgressMonitor> work, Runnable notStarted) {
            this.notStarted = notStarted;
            return () -> notStarted.run();
        }
    }

    /**
     * Небольшая пауза внутри {@code onFinish} — расширяет окно гонки с потоком, который параллельно
     * доводит задание до итога. Если реестр публикует итог раньше, чем отрабатывает {@code onFinish},
     * тест, проверяющий флаг сразу после {@code await()}, детерминированно поймает {@code false}.
     */
    static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void successfulJob_recordsStepsResultAndRunsOnFinishOnce() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
        AtomicInteger finished = new AtomicInteger();

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> {
            int v = ctx.step("first", () -> 42);
            ctx.put("value", v);
        }, finished::incrementAndGet);

        assertEquals(JobStatus.SUCCEEDED, job.status());
        assertEquals(1, finished.get());
        List<JobStep> steps = job.steps();
        assertEquals(1, steps.size());
        assertEquals("first", steps.get(0).name());
        assertEquals(StepStatus.OK, steps.get(0).status());
        assertEquals(42, job.result().get("value"));
        assertNull(job.error());
        assertNotNull(job.finishedAt());
    }

    @Test
    public void bodyException_failsJobWithMessage_andRecordsFailedStep() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
        AtomicInteger finished = new AtomicInteger();

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> {
            ctx.step("broken", () -> { throw new IllegalStateException("база занята"); });
        }, finished::incrementAndGet);

        assertEquals(JobStatus.FAILED, job.status());
        assertEquals("база занята", job.error());
        assertEquals(StepStatus.FAILED, job.steps().get(0).status());
        assertEquals("база занята", job.steps().get(0).message());
        assertEquals(1, finished.get());
    }

    @Test
    public void failedStepCaughtByBody_stillFailsJob() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> {
            try {
                ctx.step("one", () -> { throw new IllegalStateException("не вышло"); });
            } catch (IllegalStateException expected) {
                // тело продолжает — так ведёт себя сценарий с независимыми расширениями
            }
            ctx.step("two", () -> "ok");
        }, null);

        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("one"));
        assertEquals(StepStatus.OK, job.steps().get(1).status());
    }

    @Test
    public void recordedSteps_appearInOrder() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> {
            ctx.record("update-project X", StepStatus.SKIPPED, "нет проекта");
            ctx.record("note", StepStatus.WARNING, "внимание");
        }, null);

        assertEquals(JobStatus.SUCCEEDED, job.status());
        assertEquals(StepStatus.SKIPPED, job.steps().get(0).status());
        assertEquals("нет проекта", job.steps().get(0).message());
        assertEquals(StepStatus.WARNING, job.steps().get(1).status());
    }

    @Test
    public void await_returnsFalseWhileRunning_andTrueAfterFinish() {
        LatchMonitor monitor = new LatchMonitor();
        McpJobs jobs = new McpJobs(threadLauncher(monitor), Clock.systemUTC());
        CountDownLatch release = new CountDownLatch(1);

        McpJob job = jobs.start("tool_x", "проба", null,
            ctx -> assertTrue(release.await(10, TimeUnit.SECONDS)), null);

        assertFalse(job.await(Duration.ofMillis(100)));
        assertEquals(JobStatus.RUNNING, job.status());
        release.countDown();
        assertTrue(job.await(Duration.ofSeconds(10)));
        assertEquals(JobStatus.SUCCEEDED, job.status());
    }

    @Test
    public void timeout_cancelsMonitor_andFailsWithTimeoutMessage() {
        LatchMonitor monitor = new LatchMonitor();
        McpJobs jobs = new McpJobs(threadLauncher(monitor), Clock.systemUTC());
        AtomicInteger finished = new AtomicInteger();

        McpJob job = jobs.start("tool_x", "проба", Duration.ofMillis(50), ctx -> {
            assertTrue("монитор должен отмениться по лимиту", monitor.canceled.await(10, TimeUnit.SECONDS));
            throw new OperationCanceledException();
        }, finished::incrementAndGet);

        assertTrue(job.await(Duration.ofSeconds(10)));
        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("лимит времени"));
        assertEquals("timeout", job.toMap().get("cancelRequested"));
        assertEquals(1, finished.get());
    }

    @Test
    public void timeout_bodySwallowsStepCancellation_stillFailsWithTimeoutMessage() {
        LatchMonitor monitor = new LatchMonitor();
        McpJobs jobs = new McpJobs(threadLauncher(monitor), Clock.systemUTC());
        AtomicInteger finished = new AtomicInteger();

        McpJob job = jobs.start("tool_x", "проба", Duration.ofMillis(50), ctx -> {
            assertTrue("монитор должен отмениться по лимиту", monitor.canceled.await(10, TimeUnit.SECONDS));
            try {
                ctx.step("after-cancel", () -> "unreachable");
            } catch (OperationCanceledException expected) {
                // тело проглатывает отмену шага и возвращается штатно, без исключения
            }
        }, finished::incrementAndGet);

        assertTrue(job.await(Duration.ofSeconds(10)));
        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("лимит времени"));
        assertEquals(1, finished.get());
    }

    @Test
    public void cancelFromProgressView_marksJobCancelled() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> {
            ctx.monitor().setCanceled(true); // так отменяет пользователь в Progress view
            throw new OperationCanceledException();
        }, null);

        assertEquals(JobStatus.CANCELLED, job.status());
    }

    @Test
    public void bodyReturnsNormally_afterMonitorCancelled_marksCancelled() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> {
            ctx.monitor().setCanceled(true); // отмена, но тело не бросает исключение
        }, null);

        assertEquals(JobStatus.CANCELLED, job.status());
        assertEquals("задание отменено", job.error());
    }

    @Test
    public void step_afterCancellation_skipsActionAndRecordsSkippedStep() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
        AtomicInteger ran = new AtomicInteger();

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> {
            ctx.monitor().setCanceled(true);
            try {
                ctx.step("after-cancel", () -> { ran.incrementAndGet(); return "unreachable"; });
            } catch (OperationCanceledException expected) {
                // ожидаемо: step() отказывается запускать действие после отмены
            }
        }, null);

        assertEquals(0, ran.get());
        assertEquals(StepStatus.SKIPPED, job.steps().get(0).status());
        assertEquals("задание отменено", job.steps().get(0).message());
        assertEquals(JobStatus.CANCELLED, job.status());
    }

    @Test
    public void timeoutBeforeStart_failsWithTimeoutMessage_andRunsOnFinishOnce() {
        QueuedLauncher launcher = new QueuedLauncher();
        McpJobs jobs = new McpJobs(launcher, Clock.systemUTC());
        AtomicBoolean onFinishDone = new AtomicBoolean();

        // work никогда не вызывается: как если бы задание всё ещё стояло в очереди Eclipse,
        // когда наступил лимит времени. Отмену обрабатывает поток watchdog'а — sleepBriefly()
        // расширяет окно гонки с ним же, если порядок в finishNotStarted нарушен.
        McpJob job = jobs.start("tool_x", "проба", Duration.ofMillis(50), ctx -> { }, () -> {
            sleepBriefly();
            onFinishDone.set(true);
        });

        assertTrue(job.await(Duration.ofSeconds(10)));
        assertTrue("onFinish должен отработать раньше, чем await вернёт true", onFinishDone.get());
        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("лимит времени"));
    }

    @Test
    public void cancelBeforeStart_withoutTimeout_marksCancelled_andRunsOnFinishOnce() {
        QueuedLauncher launcher = new QueuedLauncher();
        McpJobs jobs = new McpJobs(launcher, Clock.systemUTC());
        AtomicInteger finished = new AtomicInteger();

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> { }, finished::incrementAndGet);
        // Как если бы Eclipse сам снял ещё не начатое задание из очереди Progress view —
        // без нашего requestCancel и без лимита времени.
        launcher.notStarted.run();

        assertEquals(JobStatus.CANCELLED, job.status());
        assertEquals("задание отменено до начала выполнения", job.error());
        assertEquals(1, finished.get());
    }

    @Test
    public void onFinish_hasRunBeforeAwaitReturnsTrue() {
        LatchMonitor monitor = new LatchMonitor();
        McpJobs jobs = new McpJobs(threadLauncher(monitor), Clock.systemUTC());
        AtomicBoolean onFinishDone = new AtomicBoolean();

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> { }, () -> {
            sleepBriefly(); // расширяет окно гонки, если порядок в execute() нарушен
            onFinishDone.set(true);
        });

        assertTrue(job.await(Duration.ofSeconds(10)));
        assertTrue("onFinish должен отработать раньше, чем await вернёт true", onFinishDone.get());
    }

    @Test
    public void finishedJobs_areEvictedBeyondFiftyAndAfterTtl() {
        TestClock clock = new TestClock();
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), clock);
        McpJob first = jobs.start("tool_x", "первое", null, ctx -> { }, null);
        for (int i = 0; i < 55; i++) {
            clock.advance(Duration.ofSeconds(1));
            jobs.start("tool_x", "задание " + i, null, ctx -> { }, null);
        }

        assertEquals(50, jobs.list().size());
        assertTrue(jobs.find(first.id()).isEmpty());

        clock.advance(Duration.ofHours(25));
        assertEquals(0, jobs.list().size());
    }

    @Test
    public void ids_areShortHexAndUnique_andToMapHasContract() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
        McpJob a = jobs.start("tool_x", "a", null, ctx -> ctx.put("k", "v"), null);
        McpJob b = jobs.start("tool_x", "b", null, ctx -> { }, null);

        assertTrue(a.id(), a.id().matches("j[0-9a-f]{8}"));
        assertNotEquals(a.id(), b.id());
        Map<String, Object> m = a.toMap();
        for (String key : List.of("jobId", "tool", "title", "status", "startedAt", "finishedAt",
                "elapsedMs", "currentStep", "steps", "result", "error")) {
            assertTrue("нет ключа " + key, m.containsKey(key));
        }
        assertEquals("SUCCEEDED", m.get("status"));
        assertEquals(Map.of("k", "v"), m.get("result"));
    }

    /** Отмена пользователем — не «org.eclipse.core.runtime.OperationCanceledException» в ответе. */
    @Test
    public void cancellation_isDescribedInRussian_notByExceptionClass() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> {
            ctx.step("wait-lock", () -> {
                ctx.monitor().setCanceled(true); // пользователь отменил в Progress view во время шага
                throw new OperationCanceledException();
            });
        }, null);

        assertEquals(JobStatus.CANCELLED, job.status());
        assertFalse(job.error(), job.error().contains("OperationCanceledException"));
        assertTrue(job.error(), job.error().contains("операция отменена"));
        assertEquals("операция отменена", job.steps().get(0).message());
    }

    @Test
    public void describe_operationCanceled_keepsItsMessage() {
        assertEquals("операция отменена", McpJobs.describe(new OperationCanceledException()));
        assertEquals("операция отменена", McpJobs.describe(new OperationCanceledException(" ")));
        assertEquals("операция отменена: ждали замок базы",
            McpJobs.describe(new OperationCanceledException("ждали замок базы")));
        assertEquals("база занята", McpJobs.describe(new IllegalStateException("база занята")));
    }

    /** Отмена — штатный исход: в лог EDT уровнем INFO и без стека; настоящий сбой — ERROR со стеком. */
    @Test
    public void cancellation_loggedAsInfoWithoutStack_failureAsErrorWithStack() {
        List<IStatus> logged = new CopyOnWriteArrayList<>();
        ILogListener listener = (status, plugin) -> logged.add(status);
        ILog log = Platform.getLog(McpJobs.class);
        log.addLogListener(listener);
        try {
            McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
            McpJob cancelled = jobs.start("tool_x", "отмена", null, ctx -> {
                ctx.monitor().setCanceled(true);
                throw new OperationCanceledException();
            }, null);
            McpJob failed = jobs.start("tool_x", "сбой", null, ctx -> {
                throw new IllegalStateException("база занята");
            }, null);

            IStatus c = onlyEntry(logged, cancelled.id());
            assertEquals(c.toString(), IStatus.INFO, c.getSeverity());
            assertNull("стек отмены в лог не пишется", c.getException());
            IStatus f = onlyEntry(logged, failed.id());
            assertEquals(f.toString(), IStatus.ERROR, f.getSeverity());
            assertNotNull(f.getException());
        } finally {
            log.removeLogListener(listener);
        }
    }

    private static IStatus onlyEntry(List<IStatus> logged, String jobId) {
        List<IStatus> mine = logged.stream().filter(s -> String.valueOf(s.getMessage()).contains(jobId)).toList();
        assertEquals("записи лога о задании " + jobId + ": " + mine, 1, mine.size());
        return mine.get(0);
    }

    @Test
    public void launcherFailure_failsJobAndStillRunsOnFinish() {
        McpJobs jobs = new McpJobs(
            (title, work, notStarted) -> { throw new IllegalStateException("нет платформы"); },
            Clock.systemUTC());
        AtomicInteger finished = new AtomicInteger();

        McpJob job = jobs.start("tool_x", "проба", null, ctx -> { }, finished::incrementAndGet);

        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("нет платформы"));
        assertEquals(1, finished.get());
    }
}
