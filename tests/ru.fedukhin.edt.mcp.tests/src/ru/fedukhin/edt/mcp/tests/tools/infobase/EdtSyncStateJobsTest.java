package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.After;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncStateJobs;

/**
 * Fix round 8 (L9): ожидание фоновых проверок EDT «Обновление состояния синхронизации проекта». Настоящие задания
 * Eclipse; «проверка EDT» здесь — задание класса {@link FakeCheck} (у EDT — внутренний класс, он сверяется по имени
 * строкой, а в тесте предикат подставной).
 */
public class EdtSyncStateJobsTest {

    /** Всё, что тест запустил, — снять в конце, даже если проверка упала. */
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final List<Job> started = new ArrayList<>();

    @After
    public void releaseAll() throws InterruptedException {
        gates.forEach(CountDownLatch::countDown);
        for (Job job : started) job.join(5000, null);
    }

    /** Как задание EDT: работает, пока не откроют ворота (или сколько задано), затем при желании ставит следующее. */
    private static class FakeCheck extends Job {
        private final CountDownLatch gate;
        private final Runnable beforeReturn;

        FakeCheck(String name, CountDownLatch gate, Runnable beforeReturn) {
            super(name);
            this.gate = gate;
            this.beforeReturn = beforeReturn;
            setSystem(true);
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            try {
                gate.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            beforeReturn.run();
            return Status.OK_STATUS;
        }
    }

    /** Постороннее задание — его ждать нельзя. */
    private static final class Other extends FakeCheck {
        Other(String name, CountDownLatch gate) {
            super(name, gate, () -> {});
        }
    }

    private CountDownLatch gate() {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        return gate;
    }

    private <J extends Job> J schedule(J job) {
        started.add(job);
        job.schedule();
        return job;
    }

    private static EdtSyncStateJobs waiter(Duration max) {
        return new EdtSyncStateJobs(() -> Job.getJobManager().find(null),
            job -> job instanceof FakeCheck && !(job instanceof Other), max);
    }

    /** Открыть ворота через {@code millis} в отдельном потоке — «проверка поработала и закончилась». */
    private static void openLater(CountDownLatch gate, long millis) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.countDown();
        }, "open-gate");
        t.setDaemon(true);
        t.start();
    }

    @Test(timeout = 30_000)
    public void nothingRunning_returnsAtOnce() {
        long t0 = System.nanoTime();

        assertNull(waiter(Duration.ofSeconds(10)).await(new NullProgressMonitor()));

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2000);
    }

    /** Ждём только проверки EDT: постороннее задание продолжает работать, а ожидание уже закончилось. */
    @Test(timeout = 30_000)
    public void joinsEdtChecksOnly_notOtherJobs() {
        Other other = schedule(new Other("посторонняя работа", gate()));
        CountDownLatch checkGate = gate();
        FakeCheck check = schedule(new FakeCheck("Обновление состояния синхронизации проекта", checkGate, () -> {}));
        openLater(checkGate, 300);

        String reason = waiter(Duration.ofSeconds(20)).await(new NullProgressMonitor());

        assertNull(reason);
        assertEquals("проверку EDT дождались", Job.NONE, check.getState());
        assertNotEquals("постороннее задание не ждали", Job.NONE, other.getState());
    }

    /**
     * EDT ставит проверку следующего проекта изнутри {@code run()} текущей — к моменту, когда {@code join} вернулся,
     * следующая уже видна; её тоже дожидаемся.
     */
    @Test(timeout = 30_000)
    public void chainedCheck_scheduledInsideRun_isAwaitedToo() {
        CountDownLatch secondGate = gate();
        AtomicReference<FakeCheck> second = new AtomicReference<>();
        CountDownLatch firstGate = gate();
        schedule(new FakeCheck("проверка проекта A", firstGate, () -> {
            FakeCheck next = new FakeCheck("проверка проекта B", secondGate, () -> {});
            started.add(next);
            second.set(next);
            next.schedule();
            openLater(secondGate, 300);
        }));
        openLater(firstGate, 100);

        String reason = waiter(Duration.ofSeconds(20)).await(new NullProgressMonitor());

        assertNull(reason);
        assertNotNull("следующая проверка запускалась", second.get());
        assertEquals("следующую проверку тоже дождались", Job.NONE, second.get().getState());
    }

    /** Проверка идёт дольше предела — причина с именем задания; ничего не брошено. */
    @Test(timeout = 30_000)
    public void checkLongerThanBound_returnsReasonWithJobName() {
        schedule(new FakeCheck("Обновление состояния синхронизации проекта", gate(), () -> {}));
        long t0 = System.nanoTime();

        String reason = waiter(Duration.ofMillis(300)).await(new NullProgressMonitor());

        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("Обновление состояния синхронизации проекта"));
        assertTrue(reason, reason.contains("проверяет состояние"));
        // m6 (fix round 9): что делать — говорит вызывающий (шаг задания, обновление проекта), здесь только факт.
        assertFalse(reason, reason.contains("повторите"));
        assertTrue("предел соблюдён: " + waited + " мс", waited < 5000);
    }

    /** Отмена задания во время ожидания — отмена, а не причина. */
    @Test(timeout = 30_000)
    public void cancelledMonitor_propagatesCancellation() {
        schedule(new FakeCheck("Обновление состояния синхронизации проекта", gate(), () -> {}));
        IProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        try {
            waiter(Duration.ofSeconds(20)).await(monitor);
            fail("expected OperationCanceledException");
        } catch (OperationCanceledException expected) {
            // ожидание отменено вместе с заданием
        }
    }
}
