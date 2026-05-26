package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseDeployer;

public class InfobaseDeployerTest {

    @Test
    public void deploy_alreadyConnected_skipsConnectAndCallsUpdate() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(true);
        when(sync.updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(false), any(IProgressMonitor.class)))
            .thenReturn(true);

        InfobaseDeployer d = new InfobaseDeployer(sync);
        InfobaseDeployer.DeployResult result = d.deploy(project, ref, false, new NullProgressMonitor());

        assertTrue(result.ok());
        assertTrue(result.durationMs() >= 0);
        verify(sync, never()).connectInfobase(any(), any(), any());
        verify(sync).updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(false), any(IProgressMonitor.class));
    }

    @Test
    public void deploy_notConnected_connectsThenUpdates() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(false);
        when(sync.updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(true), any(IProgressMonitor.class)))
            .thenReturn(true);

        InfobaseDeployer d = new InfobaseDeployer(sync);
        InfobaseDeployer.DeployResult result = d.deploy(project, ref, true, new NullProgressMonitor());

        assertTrue(result.ok());
        verify(sync).connectInfobase(eq(project), eq(ref), any(IProgressMonitor.class));
        verify(sync).updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(true), any(IProgressMonitor.class));
    }

    @Test
    public void deploy_syncException_throwsToolException() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(true);
        when(sync.updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(false), any(IProgressMonitor.class)))
            .thenThrow(new InfobaseSynchronizationException(
                new Status(IStatus.ERROR, "ru.fedukhin.edt.mcp.tests", "conflict X")));

        InfobaseDeployer d = new InfobaseDeployer(sync);
        try {
            d.deploy(project, ref, false, new NullProgressMonitor());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("conflict X"));
        }
    }

    @Test
    public void deploy_updateReturnsFalse_okFalse() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(true);
        when(sync.updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(false), any(IProgressMonitor.class)))
            .thenReturn(false);

        InfobaseDeployer d = new InfobaseDeployer(sync);
        InfobaseDeployer.DeployResult result = d.deploy(project, ref, false, new NullProgressMonitor());
        assertFalse(result.ok());
    }
}
