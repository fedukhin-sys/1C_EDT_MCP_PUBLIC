package ru.fedukhin.edt.mcp.core.jobs;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;

/** Как исполняется задание. В EDT — Eclipse Job, в тестах — синхронно в вызывающем потоке. */
@FunctionalInterface
public interface JobLauncher {

    /**
     * Запускает исполнение.
     *
     * @param work исполнение; получает монитор
     * @param notStarted вызывается, если исполнение отменено до старта {@code work} — тогда
     *     {@code work} не вызывается вовсе. Для Eclipse Job это отмена задания, ещё стоящего в
     *     очереди Progress view: {@code JobManager} снимает его без вызова {@code run()}, и
     *     дождаться отмены через монитор (который передаётся только в {@code run()}) невозможно
     * @return отмена ещё не начатого исполнения. Для уже начатого исполнения отмена идёт через
     *     монитор, переданный в {@code work}
     */
    Runnable launch(String title, Consumer<IProgressMonitor> work, Runnable notStarted);

    /**
     * Eclipse Job: виден в Progress view EDT и отменяется оттуда. Итог задания хранит
     * {@link McpJob}, поэтому Job всегда завершается без ошибки — иначе EDT показала бы
     * модальный диалог с ошибкой.
     */
    static JobLauncher eclipse() {
        return (title, work, notStarted) -> {
            AtomicBoolean started = new AtomicBoolean();
            Job job = new Job(title) {
                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    started.set(true);
                    work.accept(monitor);
                    return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
                }
            };
            job.setUser(false);
            job.setPriority(Job.LONG);
            job.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    // Снятое из очереди задание не вызывает run(): started остаётся false.
                    if (!started.get()) notStarted.run();
                }
            });
            job.schedule();
            return job::cancel;
        };
    }

    /** Синхронный запуск для тестов: задание завершается до возврата из {@link McpJobs#start}. */
    static JobLauncher synchronous() {
        return (title, work, notStarted) -> {
            work.accept(new NullProgressMonitor());
            return () -> { };
        };
    }
}
