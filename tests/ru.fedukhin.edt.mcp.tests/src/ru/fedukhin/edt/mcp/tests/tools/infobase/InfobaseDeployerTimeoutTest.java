package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseDeployer;

public class InfobaseDeployerTimeoutTest {

    private InfobaseDeployer deployer;

    @After
    public void tearDown() {
        if (deployer != null) deployer.shutdown();
    }

    @Test
    public void deployWithTimeout_happy_returnsResult() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(true);
        when(sync.updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class),
            eq(false), any(IProgressMonitor.class))).thenReturn(true);

        deployer = new InfobaseDeployer(sync);
        InfobaseDeployer.DeployResult res = deployer.deployWithTimeout(project, ref, false, 30);

        assertTrue(res.ok());
        assertTrue("durationMs should be non-negative", res.durationMs() >= 0);
    }

    @Test
    public void deployWithTimeout_exceedsBudget_throwsTimeoutAndCancelsMonitor() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(true);

        AtomicReference<IProgressMonitor> capturedMonitor = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        // Make updateInfobase block for ~3s so timeout=1 hits first.
        when(sync.updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class),
            eq(false), any(IProgressMonitor.class))).thenAnswer(inv -> {
                IProgressMonitor monitor = inv.getArgument(4);
                capturedMonitor.set(monitor);
                entered.countDown();
                Thread.sleep(3000);
                return true;
            });

        deployer = new InfobaseDeployer(sync);
        long t0 = System.currentTimeMillis();
        try {
            deployer.deployWithTimeout(project, ref, false, 1);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue("message should mention timeout, was: " + e.getMessage(),
                e.getMessage().contains("timeout"));
            assertTrue("message should mention background, was: " + e.getMessage(),
                e.getMessage().contains("background"));
        }
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue("should have returned around 1s, took " + elapsed + "ms", elapsed < 4000);

        // Wait for the worker to register the monitor before checking.
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertNotNull(capturedMonitor.get());
        assertTrue("monitor should have been canceled", capturedMonitor.get().isCanceled());
    }

    @Test
    public void deployWithTimeout_syncException_propagatesToolException() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(true);
        when(sync.updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class),
            eq(false), any(IProgressMonitor.class)))
            .thenThrow(new InfobaseSynchronizationException(
                new Status(IStatus.ERROR, "ru.fedukhin.edt.mcp.tests", "conflict X")));

        deployer = new InfobaseDeployer(sync);
        try {
            deployer.deployWithTimeout(project, ref, false, 30);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue("expected 'deploy failed:' prefix, was: " + e.getMessage(),
                e.getMessage().startsWith("deploy failed:"));
            assertTrue("expected 'conflict X' detail, was: " + e.getMessage(),
                e.getMessage().contains("conflict X"));
        }
    }

    @Test
    public void deployWithTimeout_executorReusedAcrossCalls() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(true);

        ArgumentCaptor<IProgressMonitor> monitorCaptor =
            ArgumentCaptor.forClass(IProgressMonitor.class);
        when(sync.updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class),
            eq(false), monitorCaptor.capture())).thenReturn(true);

        deployer = new InfobaseDeployer(sync);
        InfobaseDeployer.DeployResult r1 = deployer.deployWithTimeout(project, ref, false, 30);
        InfobaseDeployer.DeployResult r2 = deployer.deployWithTimeout(project, ref, false, 30);

        assertTrue(r1.ok());
        assertTrue(r2.ok());
        assertEquals("expected 2 sync invocations", 2, monitorCaptor.getAllValues().size());
    }
}
