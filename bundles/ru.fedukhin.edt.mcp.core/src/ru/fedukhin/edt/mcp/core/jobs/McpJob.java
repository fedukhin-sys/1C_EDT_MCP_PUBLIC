package ru.fedukhin.edt.mcp.core.jobs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

/**
 * Фоновое задание MCP: статус, шаги и результат.
 *
 * <p>Пишет в задание поток исполнения, читают — вызовы {@code get_job_status} из других потоков.
 * Поэтому состояние лежит в volatile-полях и потокобезопасных коллекциях, а статус выставляется
 * последним: читатель, увидевший итоговый статус, видит и всё остальное.
 */
public final class McpJob {

    private final String id;
    private final String tool;
    private final String title;
    private final Clock clock;
    private final Instant startedAt;
    private final List<JobStep> steps = new CopyOnWriteArrayList<>();
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final AtomicBoolean finishing = new AtomicBoolean();

    private volatile JobStatus status = JobStatus.RUNNING;
    private volatile Instant finishedAt;
    private volatile String error;
    private volatile String currentStep;
    private volatile String cancelReason;
    private volatile IProgressMonitor monitor;
    private volatile Runnable canceller;

    McpJob(String id, String tool, String title, Clock clock) {
        this.id = id;
        this.tool = tool;
        this.title = title;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    public String id() { return id; }

    public String tool() { return tool; }

    public String title() { return title; }

    public JobStatus status() { return status; }

    public boolean isFinished() { return status != JobStatus.RUNNING; }

    public Instant startedAt() { return startedAt; }

    public Instant finishedAt() { return finishedAt; }

    public String error() { return error; }

    public String currentStep() { return currentStep; }

    public List<JobStep> steps() { return List.copyOf(steps); }

    public Map<String, Object> result() {
        synchronized (result) {
            return new LinkedHashMap<>(result);
        }
    }

    /**
     * Ждёт завершения задания не дольше {@code wait}. Ожидание блокирующее — поток спит на
     * {@link CompletableFuture}, а не опрашивает статус в цикле.
     *
     * @return {@code true}, если задание завершено
     */
    public boolean await(Duration wait) {
        if (isFinished()) return true;
        try {
            done.get(Math.max(0L, wait.toMillis()), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return isFinished();
        } catch (ExecutionException e) {
            return isFinished();
        }
    }

    /** Полное состояние — ответ {@code get_job_status}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = summary();
        Instant end = finishedAt;
        m.put("elapsedMs", Duration.between(startedAt, end != null ? end : clock.instant()).toMillis());
        m.put("steps", steps.stream().map(JobStep::toMap).toList());
        m.put("result", result());
        m.put("error", error);
        String reason = cancelReason;
        if (reason != null) m.put("cancelRequested", reason);
        return m;
    }

    /** Краткое состояние — строка {@code list_jobs}. */
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", id);
        m.put("tool", tool);
        m.put("title", title);
        m.put("status", status.name());
        m.put("startedAt", startedAt.toString());
        Instant end = finishedAt;
        m.put("finishedAt", end == null ? null : end.toString());
        m.put("currentStep", currentStep);
        return m;
    }

    // ---- жизненный цикл: вызывает только McpJobs ----

    void attach(IProgressMonitor m) {
        monitor = m;
        if (cancelReason != null) m.setCanceled(true);
    }

    /**
     * Привязывает отмену ещё не начатого исполнения, которую вернул {@link JobLauncher#launch}.
     * Если отмену уже запросили ({@link #requestCancel}), а монитор пока не подключён — значит,
     * исполнение ещё не началось, и отменять его нужно этим способом прямо сейчас.
     */
    void bind(Runnable c) {
        canceller = c;
        if (monitor == null && cancelReason != null) c.run();
    }

    void requestCancel(String reason) {
        if (isFinished()) return;
        if (cancelReason == null) cancelReason = reason;
        IProgressMonitor m = monitor;
        if (m != null) {
            m.setCanceled(true);
        } else {
            Runnable c = canceller;
            if (c != null) c.run();
        }
    }

    String cancelReason() { return cancelReason; }

    List<String> failedStepNames() {
        return steps.stream().filter(s -> s.status() == StepStatus.FAILED).map(JobStep::name).toList();
    }

    /**
     * Первый вызов побеждает — {@code execute()} и {@code notStarted} могут вызвать это одновременно.
     * Отвязывает {@code canceller} и {@code monitor}: иначе процесс держит замыкание Eclipse Job
     * (а через него — тело задания и его захваченные объекты) всё время хранения завершённого
     * задания в реестре ({@link McpJobs#FINISHED_TTL}).
     */
    void finish(JobStatus finalStatus, String errorMessage) {
        if (!finishing.compareAndSet(false, true)) return;
        error = errorMessage;
        currentStep = null;
        canceller = null;
        monitor = null;
        finishedAt = clock.instant();
        status = finalStatus;
        done.complete(null);
    }

    JobContext context() {
        return new JobContext() {
            @Override
            public IProgressMonitor monitor() {
                IProgressMonitor m = monitor;
                return m != null ? m : new NullProgressMonitor();
            }

            @Override
            public <T> T step(String name, StepAction<T> action) throws Exception {
                if (monitor().isCanceled()) {
                    steps.add(new JobStep(name, StepStatus.SKIPPED, 0L, "задание отменено"));
                    throw new OperationCanceledException();
                }
                currentStep = name;
                long t0 = System.nanoTime();
                try {
                    T value = action.run();
                    steps.add(new JobStep(name, StepStatus.OK, millisSince(t0), null));
                    return value;
                } catch (Exception | LinkageError e) {
                    steps.add(new JobStep(name, StepStatus.FAILED, millisSince(t0), McpJobs.describe(e)));
                    throw e;
                } finally {
                    currentStep = null;
                }
            }

            @Override
            public void record(String name, StepStatus stepStatus, String message) {
                steps.add(new JobStep(name, stepStatus, 0L, message));
            }

            @Override
            public void put(String key, Object value) {
                synchronized (result) {
                    result.put(key, value);
                }
            }
        };
    }

    private static long millisSince(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }
}
