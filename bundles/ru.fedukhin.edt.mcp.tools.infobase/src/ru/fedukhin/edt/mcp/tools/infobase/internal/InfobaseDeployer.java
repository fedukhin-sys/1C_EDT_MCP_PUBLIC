package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
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
        try {
            if (!sync.isConnected(project, ref)) {
                sync.connectInfobase(project, ref, monitor);
            }
            boolean ok = sync.updateInfobase(project, ref, new NoopUpdateCallback(), force, monitor);
            return new DeployResult(ok, System.currentTimeMillis() - t0);
        } catch (InfobaseSynchronizationException e) {
            throw new ToolException("deploy failed: " + e.getMessage(), e);
        }
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
            throw new ToolException("deploy interrupted");
        }
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
