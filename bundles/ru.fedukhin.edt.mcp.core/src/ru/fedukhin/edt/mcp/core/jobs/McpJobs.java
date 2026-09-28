package ru.fedukhin.edt.mcp.core.jobs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

/**
 * Реестр фоновых заданий MCP.
 *
 * <p><b>Зачем.</b> Транспорт MCP обрывает вызов примерно через 5 минут, а загрузка {@code .dt}
 * рабочей базы и полный импорт конфигурации в проект идут десятки минут. Длинные инструменты
 * запускают задание и сразу отвечают его id, итог забирается {@code get_job_status}.
 *
 * <p><b>Синглтон процесса.</b> Инструменты живут в разных бандлах со своими Guice-инжекторами,
 * а задание, запущенное инструментом одного бандла, опрашивает инструмент другого — поэтому
 * реестр один на процесс, по образцу {@code PrivacyState}.
 *
 * <p>Задания хранятся в памяти и гибнут вместе с процессом EDT. Завершённые вытесняются:
 * остаются последние {@value #MAX_FINISHED} и не старше {@link #FINISHED_TTL}.
 */
public final class McpJobs {

    static final int MAX_FINISHED = 50;
    static final Duration FINISHED_TTL = Duration.ofHours(24);
    static final String TIMEOUT_REASON = "timeout";
    static final String CANCELLED = "операция отменена";

    private static volatile McpJobs instance;

    private final JobLauncher launcher;
    private final Clock clock;
    private final Map<String, McpJob> jobs = new ConcurrentHashMap<>();
    private ScheduledThreadPoolExecutor watchdog;

    public McpJobs(JobLauncher launcher, Clock clock) {
        this.launcher = launcher;
        this.clock = clock;
    }

    /** Реестр процесса: задания исполняются как Eclipse Job. */
    public static McpJobs get() {
        McpJobs local = instance;
        if (local == null) {
            synchronized (McpJobs.class) {
                local = instance;
                if (local == null) {
                    local = new McpJobs(JobLauncher.eclipse(), Clock.systemUTC());
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Запускает задание.
     *
     * @param timeout лимит времени; по истечении монитор задания отменяется. {@code null} — без лимита
     * @param onFinish выполняется ровно один раз по завершении задания при любом исходе, например
     *     снимает замок базы, взятый вызывающим инструментом. {@code null} — ничего
     */
    public McpJob start(String tool, String title, Duration timeout, JobBody body, Runnable onFinish) {
        prune();
        McpJob job = new McpJob(newId(), tool, title, clock);
        jobs.put(job.id(), job);
        Runnable finishOnce = once(onFinish);
        ScheduledFuture<?> timer = timeout == null || timeout.isZero() || timeout.isNegative() ? null
            : watchdog().schedule(() -> job.requestCancel(TIMEOUT_REASON), timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        Runnable notStarted = () -> finishNotStarted(job, timeout, timer, finishOnce);
        try {
            Runnable canceller = launcher.launch("EDT MCP: " + tool + " — " + title,
                monitor -> execute(job, body, monitor, timeout, timer, finishOnce), notStarted);
            job.bind(canceller);
        } catch (RuntimeException | LinkageError e) {
            try {
                if (timer != null) timer.cancel(false);
                finishOnce.run();
            } finally {
                if (!job.isFinished()) job.finish(JobStatus.FAILED, "задание не запустилось: " + describe(e));
            }
        }
        return job;
    }

    /**
     * Завершает задание, отменённое до старта {@code work} — например, Eclipse снял его из очереди
     * Progress view, не вызвав {@code run()}. Отдельно от {@link #execute}, у которого есть монитор
     * и тело задания уже успело что-то сделать.
     *
     * <p>Как и в {@link #execute}, {@code onFinish} выполняется до {@link McpJob#finish} — тот же
     * порядок, что уберегает внешнего наблюдателя ({@code get_job_status}, {@code job.await()}) от
     * «завершённого» статуса, пока замок базы ещё не снят.
     */
    private static void finishNotStarted(McpJob job, Duration timeout, ScheduledFuture<?> timer,
                                          Runnable onFinish) {
        JobStatus finalStatus;
        String message;
        if (TIMEOUT_REASON.equals(job.cancelReason())) {
            long minutes = timeout == null ? 0 : timeout.toMinutes();
            finalStatus = JobStatus.FAILED;
            message = "превышен лимит времени задания (" + minutes + " мин): задание не успело начаться";
        } else {
            finalStatus = JobStatus.CANCELLED;
            message = "задание отменено до начала выполнения";
        }
        try {
            if (timer != null) timer.cancel(false);
            onFinish.run();
        } finally {
            job.finish(finalStatus, message);
        }
    }

    public Optional<McpJob> find(String id) {
        return Optional.ofNullable(id == null ? null : jobs.get(id));
    }

    /** Задания этой инстанции EDT, новые сверху. */
    public List<McpJob> list() {
        prune();
        return jobs.values().stream()
            .sorted(Comparator.comparing(McpJob::startedAt).reversed())
            .toList();
    }

    /**
     * Текст ошибки для статуса задания и шага: сообщение исключения, а без него — имя класса.
     * Отмена ({@link OperationCanceledException}) — «операция отменена» (и её сообщение через
     * двоеточие, если оно есть), а не имя класса: это штатный исход, а не сбой.
     */
    public static String describe(Throwable e) {
        String message = e.getMessage();
        boolean blank = message == null || message.isBlank();
        if (e instanceof OperationCanceledException) {
            return blank ? CANCELLED : CANCELLED + ": " + message;
        }
        return blank ? e.getClass().getName() : message;
    }

    /**
     * Исполняет тело задания и доводит его до итога.
     *
     * <p>Итог сперва вычисляется в локальных переменных, и только после {@code timer.cancel} и
     * {@code onFinish} (снятие замка вызывающим инструментом) записывается в {@code job} — методом
     * {@link McpJob#finish}, последним действием, во вложенном {@code finally}. Логирование,
     * {@code timer.cancel}/{@code onFinish} и {@code finish} — на отдельных уровнях try/finally:
     * исключение из логирования не пропустит {@code onFinish}, а исключение из {@code onFinish} —
     * {@code finish}. Иначе внешний наблюдатель ({@code get_job_status}, {@code job.await()})
     * увидел бы задание завершённым до того, как {@code onFinish} успел снять замок базы, взятый
     * вызывавшим инструментом.
     */
    private void execute(McpJob job, JobBody body, IProgressMonitor monitor, Duration timeout,
                         ScheduledFuture<?> timer, Runnable onFinish) {
        job.attach(monitor);
        JobStatus finalStatus;
        String message;
        Throwable failure = null;
        try {
            body.run(job.context());
            String timeoutMessage = timeoutMessage(job, timeout);
            if (timeoutMessage != null) {
                finalStatus = JobStatus.FAILED;
                message = timeoutMessage;
            } else if (monitor.isCanceled()) {
                finalStatus = JobStatus.CANCELLED;
                message = "задание отменено";
            } else {
                List<String> failed = job.failedStepNames();
                if (failed.isEmpty()) {
                    finalStatus = JobStatus.SUCCEEDED;
                    message = null;
                } else {
                    finalStatus = JobStatus.FAILED;
                    message = "шаги с ошибками: " + String.join(", ", failed);
                }
            }
        } catch (Throwable e) {
            failure = e;
            String timeoutMessage = timeoutMessage(job, timeout);
            if (timeoutMessage != null) {
                finalStatus = JobStatus.FAILED;
                message = timeoutMessage + ": " + describe(e);
            } else if (monitor.isCanceled()) {
                finalStatus = JobStatus.CANCELLED;
                message = "задание отменено: " + describe(e);
            } else {
                finalStatus = JobStatus.FAILED;
                message = describe(e);
            }
        }
        try {
            if (failure != null) log(job, failure, message);
        } finally {
            try {
                if (timer != null) timer.cancel(false);
                onFinish.run();
            } finally {
                job.finish(finalStatus, message);
            }
        }
        if (failure instanceof VirtualMachineError vme) throw vme;
    }

    /** Сообщение о превышении лимита времени, если отмена вызвана именно им — иначе {@code null}. */
    private static String timeoutMessage(McpJob job, Duration timeout) {
        if (!TIMEOUT_REASON.equals(job.cancelReason())) return null;
        long minutes = timeout == null ? 0 : timeout.toMinutes();
        return "превышен лимит времени задания (" + minutes + " мин)";
    }

    private static Runnable once(Runnable action) {
        AtomicBoolean ran = new AtomicBoolean();
        return () -> {
            if (action == null || !ran.compareAndSet(false, true)) return;
            try {
                action.run();
            } catch (RuntimeException e) {
                warn("EDT MCP: ошибка при завершении задания", e);
            }
        };
    }

    private void prune() {
        Instant horizon = clock.instant().minus(FINISHED_TTL);
        jobs.values().removeIf(j -> j.isFinished() && j.finishedAt().isBefore(horizon));
        List<McpJob> finished = jobs.values().stream()
            .filter(McpJob::isFinished)
            .sorted(Comparator.comparing(McpJob::finishedAt))
            .toList();
        for (int i = 0; i < finished.size() - MAX_FINISHED; i++) {
            jobs.remove(finished.get(i).id());
        }
    }

    private String newId() {
        String id;
        do {
            id = String.format("j%08x", ThreadLocalRandom.current().nextInt());
        } while (jobs.containsKey(id));
        return id;
    }

    private synchronized ScheduledThreadPoolExecutor watchdog() {
        if (watchdog == null) {
            watchdog = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "edt-mcp-job-watchdog");
                t.setDaemon(true);
                return t;
            });
            watchdog.setRemoveOnCancelPolicy(true);
        }
        return watchdog;
    }

    /**
     * Сбой — {@code ERROR} со стеком. Отмена ({@link OperationCanceledException}: Progress view,
     * лимит времени) — штатный исход: {@code INFO} с итоговым текстом задания и без стека, иначе
     * каждая отмена пользователем выглядела бы в логе EDT аварией.
     */
    private static void log(McpJob job, Throwable e, String jobMessage) {
        try {
            if (e instanceof OperationCanceledException) {
                Platform.getLog(McpJobs.class).log(Status.info(
                    "EDT MCP: задание " + job.id() + " (" + job.tool() + ") — " + jobMessage));
                return;
            }
            Platform.getLog(McpJobs.class).log(Status.error(
                "EDT MCP: задание " + job.id() + " (" + job.tool() + ") завершилось ошибкой", e));
        } catch (RuntimeException | LinkageError ignored) {
            // Логирование — best-effort: вне OSGi лога может не быть.
        }
    }

    private static void warn(String message, Throwable e) {
        try {
            Platform.getLog(McpJobs.class).log(Status.warning(message, e));
        } catch (RuntimeException | LinkageError ignored) {
            // см. log()
        }
    }
}
