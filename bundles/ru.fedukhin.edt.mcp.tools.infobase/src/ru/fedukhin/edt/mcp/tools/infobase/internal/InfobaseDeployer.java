package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.ToolException;

@Singleton
public class InfobaseDeployer {

    public record DeployResult(boolean ok, long durationMs) { }

    private static final long SHUTDOWN_WAIT_SEC = 5;

    private final IInfobaseSynchronizationManager sync;
    private volatile ExecutorService executor;

    @Inject
    public InfobaseDeployer(IInfobaseSynchronizationManager sync) {
        this.sync = sync;
    }

    /**
     * Package-public work unit; called directly by {@link #deployWithTimeout} on the executor thread,
     * and used by existing unit tests that mock at the Deployer level.
     */
    public DeployResult deploy(IProject project, InfobaseReference ref, boolean force,
                                IProgressMonitor monitor) throws ToolException {
        long t0 = System.currentTimeMillis();
        Boolean connected = invokeIsConnected(project, ref);
        try {
            if (connected == null || !connected) {
                sync.connectInfobase(project, ref, monitor);
            }
        } catch (InfobaseSynchronizationException e) {
            if (connected != null) {
                throw new ToolException("deploy failed: " + e.getMessage(), e);
            }
            // Старый EDT: узнать, подключена ли ИБ, нечем, поэтому connectInfobase зовётся
            // вслепую и на уже подключённой базе законно ругается. Реальную проблему,
            // если она есть, покажет следующий шаг — updateInfobase.
        }
        boolean ok = invokeUpdateInfobase(project, ref, force, monitor);
        return new DeployResult(ok, System.currentTimeMillis() - t0);
    }

    /**
     * Вызывает {@code IInfobaseSynchronizationManager.updateInfobase} через рефлексию.
     *
     * <p>Возвращаемый тип этого метода менялся между версиями 1C:EDT:
     * {@code boolean} (dt.platform.services.core ≤ 19.x) → {@code IStatus} (core 21.0.0+, EDT 2026.x).
     * Прямой вызов, скомпилированный против одной версии, падает на другой с
     * {@code NoSuchMethodError} (JVM резолвит метод вместе с возвращаемым типом). Рефлексия
     * ищет метод только по имени и типам параметров (они стабильны), поэтому один байткод
     * работает на обеих ветках EDT. Результат интерпретируется в {@link #interpretUpdateResult}.
     */
    /**
     * Вызывает {@code IInfobaseSynchronizationManager.isConnected} через рефлексию.
     *
     * <p>Метод появился только в dt.platform.services.core 21 (EDT 2026.x): на EDT ≤2025.1
     * (core 18/19) прямой вызов давал {@code NoSuchMethodError} ещё до updateInfobase, то есть
     * deploy_project был неработоспособен на всей ветке 2023.x, хотя плагин заявлен dual-version.
     *
     * @return {@code true}/{@code false} — ответ платформы; {@code null} — метода нет (старый EDT)
     */
    protected Boolean invokeIsConnected(IProject project, InfobaseReference ref) throws ToolException {
        try {
            Method m = IInfobaseSynchronizationManager.class.getMethod("isConnected",
                IProject.class, InfobaseReference.class);
            return (Boolean) m.invoke(sync, project, ref);
        } catch (NoSuchMethodError | NoSuchMethodException e) {
            return null;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ToolException("deploy failed: " + cause.getMessage(), cause);
        } catch (IllegalAccessException e) {
            throw new ToolException("deploy failed: cannot call isConnected: " + e.getMessage(), e);
        }
    }

    private boolean invokeUpdateInfobase(IProject project, InfobaseReference ref, boolean force,
                                         IProgressMonitor monitor) throws ToolException {
        IInfobaseUpdateCallback callback = NoopUpdateCallback.create();
        try {
            Method m = IInfobaseSynchronizationManager.class.getMethod("updateInfobase",
                IProject.class, InfobaseReference.class, IInfobaseUpdateCallback.class,
                boolean.class, IProgressMonitor.class);
            Object res = m.invoke(sync, project, ref, callback, force, monitor);
            return interpretUpdateResult(res);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InfobaseSynchronizationException) {
                // Старый EDT сигнализирует конфликт checked-исключением.
                throw new ToolException("deploy failed: " + cause.getMessage(), cause);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error er) {
                throw er;
            }
            throw new ToolException("deploy failed: "
                + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ToolException("deploy failed: несовместимый EDT API updateInfobase: "
                + e.getMessage(), e);
        }
    }

    /**
     * Приводит результат {@code updateInfobase} к {@code boolean ok}, поддерживая обе версии API:
     * <ul>
     *   <li>{@link Boolean} (старый EDT) — возвращается как есть;</li>
     *   <li>{@link IStatus} (EDT 2026.x) — {@code ERROR} поднимается как {@link ToolException}
     *       (иначе провал синхронизации проглатывается молча), {@code CANCEL} → {@code false},
     *       {@code OK}/{@code INFO}/{@code WARNING} → {@code true}.</li>
     * </ul>
     */
    private static boolean interpretUpdateResult(Object res) throws ToolException {
        if (res instanceof Boolean b) {
            return b;
        }
        if (res instanceof IStatus s) {
            int sev = s.getSeverity();
            if (sev == IStatus.ERROR) {
                throw new ToolException("deploy failed: " + s.getMessage());
            }
            return sev != IStatus.CANCEL;
        }
        // Неизвестный тип результата, но исключения не было — считаем deploy успешным.
        return true;
    }

    /**
     * Best-effort cancel: on timeout we call {@code monitor.setCanceled(true)} and
     * {@code future.cancel(true)}, but the underlying EDT sync operation may continue
     * running in the background — it's up to the EDT pipeline to honour cancellation.
     */
    public DeployResult deployWithTimeout(IProject project, InfobaseReference ref,
                                           boolean force, int timeoutSeconds) throws ToolException {
        ExecutorService exec = ensureExecutor();
        NullProgressMonitor monitor = new NullProgressMonitor();
        Future<DeployResult> f = exec.submit(() -> deploy(project, ref, force, monitor));
        try {
            return f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            monitor.setCanceled(true);
            f.cancel(true);
            discardExecutor(exec);
            throw new ToolException("deploy timeout after " + timeoutSeconds
                + "s; deploy may still be running in background");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolException te) throw te;
            throw new ToolException("deploy failed: "
                + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            discardExecutor(exec);
            throw new ToolException("deploy interrupted");
        }
    }

    /**
     * Отвязывает executor, поток которого мог остаться занят брошенной задачей.
     *
     * <p>Executor однопоточный, а отмена — best-effort: EDT-синхронизация не обязана
     * реагировать ни на {@code monitor.setCanceled}, ни на interrupt. Если оставить тот же
     * executor, следующий deploy молча встанет в очередь за зависшей задачей и упрётся в свой
     * таймаут, ничего не сделав. Поэтому executor заменяется — брошенный поток доработает сам
     * (он daemon) и умрёт вместе с ним.
     *
     * <p>{@code shutdownNow()} без {@code awaitTermination}: ждать зависшую задачу здесь
     * значило бы заблокировать вызывающий поток ровно на то время, от которого мы уходим.
     */
    private synchronized void discardExecutor(ExecutorService exec) {
        if (executor == exec) {
            executor = null;
        }
        exec.shutdownNow();
    }

    public synchronized void shutdown() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_WAIT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    private synchronized ExecutorService ensureExecutor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "edt-mcp-deployer");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }
}
