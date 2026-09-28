package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.OperationCanceledException;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.ipc.InterProcessLock;
import ru.fedukhin.edt.mcp.core.ipc.LockTimeoutException;
import ru.fedukhin.edt.mcp.core.jobs.JobBody;
import ru.fedukhin.edt.mcp.core.jobs.JobContext;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.core.jobs.StepStatus;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyState;

/**
 * Запуск задания над базой под межпроцессным замком.
 *
 * <p>Замок берётся в потоке вызова — чтобы занятая база давала отказ сразу, а не через
 * {@code get_job_status}, — и снимается самим заданием по завершении при любом исходе. Слой
 * внутри процесса у {@link InterProcessLock} — семафор, он не привязан к потоку, а файловый
 * замок принадлежит процессу, поэтому снимать замок из потока задания корректно.
 */
public class InfobaseJobs {

    static final Duration LOCK_WAIT = Duration.ofSeconds(10);

    /**
     * Первый шаг каждого задания (fix round 8, L9): дождаться фоновых проверок EDT «Обновление состояния
     * синхронизации проекта» ({@link EdtSyncStateJobs}) — до резервной выгрузки, загрузки, проверки проектов и
     * всего, что трогает агент конфигуратора или состояние синхронизации.
     */
    public static final String EDT_CHECKS_STEP = "wait-edt-checks";

    private final Supplier<McpJobs> jobs;
    private final Duration lockWait;
    private final EdtSyncStateJobs edtJobs;

    @Inject
    public InfobaseJobs(EdtSyncStateJobs edtJobs) {
        this(McpJobs::get, LOCK_WAIT, edtJobs);
    }

    public InfobaseJobs(Supplier<McpJobs> jobs, Duration lockWait) {
        this(jobs, lockWait, new EdtSyncStateJobs());
    }

    /** @param edtJobs фоновые проверки EDT, которых дожидается первый шаг каждого задания (fix round 8, L9) */
    public InfobaseJobs(Supplier<McpJobs> jobs, Duration lockWait, EdtSyncStateJobs edtJobs) {
        this.jobs = jobs;
        this.lockWait = lockWait;
        this.edtJobs = edtJobs;
    }

    public Map<String, Object> start(String tool, String title, InfobaseReference infobase, String subject,
                                     Duration timeout, JobBody body, Map<String, Object> echo) throws ToolException {
        return start(tool, title, InfobaseLockKey.of(infobase), subject, timeout, body, echo);
    }

    /**
     * @param lockKey ключ замка; для ещё не созданной базы — {@link InfobaseLockKey#build} от её
     *     заранее выбранного UUID
     * @param subject что обрабатывается (обычно имя проекта) — попадает в текст чужого отказа
     */
    public Map<String, Object> start(String tool, String title, String lockKey, String subject,
                                     Duration timeout, JobBody body, Map<String, Object> echo) throws ToolException {
        String holder = tool + " " + subject + " pid=" + ProcessHandle.current().pid();
        InterProcessLock lock;
        try {
            lock = InterProcessLock.acquire(lockKey, holder, lockWait);
        } catch (LockTimeoutException e) {
            throw new ToolException(e.getMessage());
        } catch (IOException e) {
            throw new ToolException("не удалось взять замок '" + lockKey + "': " + e.getMessage(), e);
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            // Повторное закрытие отпустило бы семафор дважды и сломало взаимоисключение.
            if (released.compareAndSet(false, true)) lock.close();
        };
        // L9 (fix round 8): первым шагом — фоновые проверки EDT после запуска или открытия проектов; иначе наше
        // закрытие сеанса агента и отметка сталкиваются с их синхронизацией. Не дождались — ничего не тронуто.
        JobBody afterEdtChecks = ctx -> {
            ctx.step(EDT_CHECKS_STEP, () -> {
                String reason = edtJobs.await(ctx.monitor());
                if (reason != null) throw new ToolException(reason + " — повторите позже; ничего не тронуто");
                return null;
            });
            body.run(ctx);
        };
        McpJob job;
        try {
            job = jobs.get().start(tool, title, timeout, afterEdtChecks, release);
        } catch (RuntimeException | LinkageError e) {
            release.run();
            throw new ToolException("задание не запустилось: " + McpJobs.describe(e), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.id());
        out.put("status", job.status().name());
        out.put("tool", tool);
        if (echo != null) out.putAll(echo);
        out.put("poll", "get_job_status");
        return out;
    }

    /**
     * Шаг {@code pii-flag}: флаг «в базе реальные ПДн» — обратно в {@code true} (fail-closed) по
     * имени базы и, если он есть, по её UUID: по обоим ключам его читает {@code query_event_log}.
     *
     * <p>Вызывается ДО загрузки {@code .dt}: загрузку можно отменить, она может упереться в лимит
     * времени или упасть на середине, а база к тому моменту уже хранит рабочие данные. После
     * загрузки шаг просто не выполнился бы, и у базы остался бы {@code false} — например, от прежней
     * базы с тем же именем (при удалении базы флаги не чистятся). Не удалось сбросить флаг — шаг
     * падает, и до загрузки дело не доходит. Сценарий 1 вызывает шаг дважды: ещё до создания базы
     * (CREATEINFOBASE монитор не отменяет) и прямо перед загрузкой; повторный сброс идемпотентен.
     */
    public static void resetPiiFlag(JobContext ctx, String infobaseName, UUID infobaseUuid) throws Exception {
        ctx.step("pii-flag", () -> {
            PrivacyState.flags().setFlag(infobaseName, true);
            if (infobaseUuid != null) PrivacyState.flags().setFlag(infobaseUuid.toString(), true);
            return null;
        });
        ctx.put("piiFlagReset", true);
    }

    /**
     * Шаг задания «проект ← база» без контекста «после загрузки» (примитив {@code update_project_from_infobase},
     * сценарий 1): см. вариант с {@link AfterLoad}.
     */
    public static ProjectFromInfobaseUpdater.Outcome updateProject(JobContext ctx, ProjectFromInfobaseUpdater updater,
                                                                   IProject project, InfobaseReference infobase,
                                                                   boolean discardProjectChanges) throws Exception {
        return updateProject(ctx, updater, project, infobase, discardProjectChanges, null);
    }

    /**
     * Шаг задания «проект ← база». Предупреждение итога (правки проекта остались и после обновления, либо не
     * удалось обновить ресурсы проекта) записывается отдельным шагом {@code WARNING}: иначе клиент увидел бы только
     * успешный шаг обновления. Флаг — всегда явно (fix round 6, N3): {@code false} — окончательная проверка проекта
     * внутри задания, после ожидания модели EDT (fix round 5, O1), и сравнение базы с записанным состоянием;
     * {@code true} — полная замена проекта содержимым базы.
     *
     * <p><b>После загрузки</b> (fix round 9, m2). {@code afterLoad} — шаг идёт после загрузки {@code .dt}/{@code .cfe}
     * в базу: отказ O1 получает текст {@link AfterLoad#refusal}, любой другой сбой шага — {@link AfterLoad#failure}
     * (исходная причина — в тексте), предупреждение итога — {@link AfterLoad#decorate}. Отмена остаётся отменой
     * ({@link OperationCanceledException}: реестр доведёт задание до {@code CANCELLED} или «лимит времени»), но и её
     * текст несёт указание «после загрузки». {@code null} — как было.
     */
    public static ProjectFromInfobaseUpdater.Outcome updateProject(JobContext ctx, ProjectFromInfobaseUpdater updater,
                                                                   IProject project, InfobaseReference infobase,
                                                                   boolean discardProjectChanges, AfterLoad afterLoad)
            throws Exception {
        ProjectFromInfobaseUpdater.Outcome outcome = ctx.step("update-project " + project.getName(), () -> {
            if (afterLoad == null) return updater.update(project, infobase, discardProjectChanges, ctx.monitor());
            try {
                return afterLoad.decorate(updater.update(project, infobase, discardProjectChanges, ctx.monitor()));
            } catch (ProjectsAtRiskException e) {
                throw new ProjectsAtRiskException(e.atRisk(), afterLoad.refusal(e.atRisk()));
            } catch (OperationCanceledException e) {
                throw new OperationCanceledException(afterLoad.failure(project.getName(), "обновление прервано"));
            } catch (Exception e) {
                throw new ToolException(afterLoad.failure(project.getName(), McpJobs.describe(e)), e);
            }
        });
        return recordWarning(ctx, project, outcome);
    }

    /**
     * Шаг {@code check-projects} (fix round 5, O1): проекты, которые задание сейчас перезапишет, проверяются
     * заново — после ожидания модели EDT каждого ({@code ProjectFromInfobaseUpdater.projectsAtRiskAfterModelSync}).
     * Синхронная проверка в {@code call()} инструмента свежую правку не видит: {@code write_module} пишет файл и
     * не ждёт, а быстрая проверка EDT {@code isProjectDirty} ~4 с после записи (и дольше, пока правки идут)
     * отвечает «чисто». Есть риск — шаг и задание падают с текстом {@code refusal} по пунктам риска (у сценариев 2 и
     * 3 — {@link SyncV2#atRiskBeforeLoadMessage}, тот же, что при вызове; fix round 9, I2). Вызывать сразу после
     * {@link #EDT_CHECKS_STEP} (его добавляет {@link #start} первым шагом каждого задания; m5), до резервной выгрузки,
     * загрузки {@code .dt}/{@code .cfe} и обновления проектов.
     */
    public static void checkProjects(JobContext ctx, ProjectFromInfobaseUpdater updater, List<IProject> projects,
                                     InfobaseReference infobase, Function<List<String>, String> refusal)
            throws Exception {
        ctx.step("check-projects", () -> {
            List<String> atRisk = updater.projectsAtRiskAfterModelSync(projects, infobase);
            if (!atRisk.isEmpty()) throw new ToolException(refusal.apply(atRisk));
            return null;
        });
    }

    private static ProjectFromInfobaseUpdater.Outcome recordWarning(JobContext ctx, IProject project,
            ProjectFromInfobaseUpdater.Outcome outcome) {
        if (outcome.warning() != null) {
            ctx.record("verify-project " + project.getName(), StepStatus.WARNING, outcome.warning());
        }
        return outcome;
    }
}
