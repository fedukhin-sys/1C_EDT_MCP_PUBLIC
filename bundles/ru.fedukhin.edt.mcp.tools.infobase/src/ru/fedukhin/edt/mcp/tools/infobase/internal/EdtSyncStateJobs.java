package ru.fedukhin.edt.mcp.tools.infobase.internal;

import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Ожидание фоновых проверок EDT «Обновление состояния синхронизации проекта» (живой прогон 1.24.0, L9).
 *
 * <p><b>Что это.</b> Когда у проекта стартует контекст (запуск EDT, открытие или создание проекта), EDT ставит
 * в очередь проверку его состояния синхронизации с базами — задание
 * {@code InfobaseSynchronizationManager$ProjectSynchronizationStateUpdateScheduler$SynchronizationStateUpdateJob}:
 * оно открывает агент конфигуратора базы и само забирает изменения со своим обработчиком. Проекты — по одному:
 * следующее задание ставится изнутри {@code run()} текущего ({@code removeCurrentJobAndTakeNext}), правила
 * планирования у заданий нет. Столкновение с ним на живом прогоне: наше закрытие сеанса агента убило агент,
 * который оно только что открыло, а его синхронизация была активна, когда мы отмечали проект, — отметка не
 * прошла, и всё дальше каскадом упиралось в «есть правки».
 *
 * <p><b>Как ждём.</b> Задания находятся в {@code Job.getJobManager().find(null)} по имени класса — строкой,
 * класс внутренний; каждому — {@link Job#join(long, IProgressMonitor)} с остатком срока (блокирующее ожидание,
 * не холостой цикл); затем поиск заново: следующее задание цепочки к моменту возврата {@code join} уже поставлено.
 * Ждём ВСЕ такие задания, не только своего проекта: поставленное в очередь, но ещё не запланированное, не видно,
 * а цепочка бывает только сразу после запуска или открытия. Предел — {@link #DEFAULT_MAX}; отмена задания
 * пробрасывается.
 */
public class EdtSyncStateJobs {

    /** Класс задания EDT — только строкой: он внутренний, статической ссылки быть не должно. */
    static final String JOB_CLASS = "com._1c.g5.v8.dt.internal.platform.services.core.infobases.sync."
        + "InfobaseSynchronizationManager$ProjectSynchronizationStateUpdateScheduler$SynchronizationStateUpdateJob";

    public static final Duration DEFAULT_MAX = Duration.ofMinutes(10);

    private final Supplier<Job[]> jobs;
    private final Predicate<Job> edtCheck;
    private final Duration max;

    @Inject
    public EdtSyncStateJobs() {
        this(() -> Job.getJobManager().find(null), job -> JOB_CLASS.equals(job.getClass().getName()), DEFAULT_MAX);
    }

    /**
     * @param jobs все задания Eclipse ({@code IJobManager.find(null)})
     * @param edtCheck задание — проверка EDT (в бою — по имени класса; тесты подставляют свой признак)
     * @param max сколько ждать всего; не меньше 1 мс
     */
    public EdtSyncStateJobs(Supplier<Job[]> jobs, Predicate<Job> edtCheck, Duration max) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.edtCheck = Objects.requireNonNull(edtCheck, "edtCheck");
        this.max = DesignerBatch.requireAtLeastOneMillisecond(max, "max");
    }

    /**
     * Дожидается, пока проверок EDT не останется.
     *
     * @return {@code null} — проверок нет (или все закончились); иначе причина: не дождались за {@link #max} —
     *     только факт, что делать дальше, говорит вызывающий (шаг задания, обновление проекта; fix round 9, m6)
     * @throws OperationCanceledException задание отменено (или поток прерван) во время ожидания
     */
    public String await(IProgressMonitor monitor) {
        long deadline = System.nanoTime() + max.toNanos();
        while (true) {
            List<Job> running = running();
            if (running.isEmpty()) return null;
            for (Job job : running) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                // join(0, …) — «без предела»: остаток в ноль — уже не дождались.
                if (remainingMs <= 0) return tooLong(running());
                try {
                    if (!job.join(remainingMs, monitor)) return tooLong(running());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OperationCanceledException("ожидание фоновых проверок EDT прервано");
                }
            }
        }
    }

    private List<Job> running() {
        List<Job> out = new ArrayList<>();
        Job[] all = jobs.get();
        if (all == null) return out;
        for (Job job : all) {
            if (job != null && edtCheck.test(job)) out.add(job);
        }
        return out;
    }

    private String tooLong(List<Job> stillRunning) {
        Set<String> names = new LinkedHashSet<>();
        for (Job job : stillRunning) names.add("«" + job.getName() + "»");
        return "EDT после запуска или открытия проектов ещё проверяет состояние их синхронизации с базами"
            + (names.isEmpty() ? "" : " (фоновые задания EDT: " + String.join(", ", names) + ")")
            + " дольше " + format(max);
    }

    private static String format(Duration d) {
        long seconds = Math.max(1, (d.toMillis() + 999) / 1000);
        return seconds % 60 == 0 ? (seconds / 60) + " мин" : seconds + " с";
    }
}
