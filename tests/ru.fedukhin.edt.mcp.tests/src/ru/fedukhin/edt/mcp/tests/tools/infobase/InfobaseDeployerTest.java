package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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

/**
 * Стабы заданы через нетипизированный {@code doReturn(...).when(mock).updateInfobase(...)}, а не
 * {@code when(...).thenReturn(...)}: возвращаемый тип {@code updateInfobase} различается между
 * версиями EDT (boolean → IStatus), и нетипизированный стаб не привязывает исходник теста к
 * конкретному типу. Сами значения (OK/ERROR/CANCEL-статусы) соответствуют EDT 2026.x, против
 * которого идёт сборка. {@code InfobaseDeployer} вызывает {@code updateInfobase} через рефлексию,
 * поэтому вызов на mock резолвится штатно.
 */
public class InfobaseDeployerTest {

    @Test
    public void deploy_alreadyConnected_skipsConnectAndCallsUpdate() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(true);
        doReturn(Status.OK_STATUS).when(sync).updateInfobase(
            eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(false), any(IProgressMonitor.class));

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
        doReturn(Status.OK_STATUS).when(sync).updateInfobase(
            eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(true), any(IProgressMonitor.class));

        InfobaseDeployer d = new InfobaseDeployer(sync);
        InfobaseDeployer.DeployResult result = d.deploy(project, ref, true, new NullProgressMonitor());

        assertTrue(result.ok());
        verify(sync).connectInfobase(eq(project), eq(ref), any(IProgressMonitor.class));
        verify(sync).updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(true), any(IProgressMonitor.class));
    }

    /**
     * На EDT ≤2025.1 (dt.platform.services.core 18/19) метода {@code isConnected} в интерфейсе нет
     * вовсе — прямой вызов давал NoSuchMethodError ещё до updateInfobase, то есть deploy_project
     * не работал на всей ветке 2023.x. Отсутствие метода в юнит-тесте не сымитировать (компилируемся
     * против core 23), поэтому проверяется сама ветка фолбэка: узнать состояние нечем.
     */
    @Test
    public void deploy_oldEdtWithoutIsConnected_connectsBlindlyAndUpdates() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        doReturn(Status.OK_STATUS).when(sync).updateInfobase(
            eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(false), any(IProgressMonitor.class));

        InfobaseDeployer d = new InfobaseDeployer(sync) {
            @Override protected Boolean invokeIsConnected(IProject p, InfobaseReference r) {
                return null; // старый EDT: метода нет
            }
        };
        InfobaseDeployer.DeployResult result = d.deploy(project, ref, false, new NullProgressMonitor());

        assertTrue(result.ok());
        verify(sync).connectInfobase(eq(project), eq(ref), any(IProgressMonitor.class));
    }

    /** На старом EDT «уже подключена» прилетает исключением — деплой обязан продолжиться. */
    @Test
    public void deploy_oldEdtAlreadyConnected_ignoresConnectFailureAndUpdates() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        doThrow(new InfobaseSynchronizationException(Status.error("уже подключена")))
            .when(sync).connectInfobase(eq(project), eq(ref), any(IProgressMonitor.class));
        doReturn(Status.OK_STATUS).when(sync).updateInfobase(
            eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(false), any(IProgressMonitor.class));

        InfobaseDeployer d = new InfobaseDeployer(sync) {
            @Override protected Boolean invokeIsConnected(IProject p, InfobaseReference r) {
                return null;
            }
        };
        InfobaseDeployer.DeployResult result = d.deploy(project, ref, false, new NullProgressMonitor());

        assertTrue("на старом EDT отказ connectInfobase не должен валить деплой", result.ok());
        verify(sync).updateInfobase(eq(project), eq(ref), any(IInfobaseUpdateCallback.class),
            eq(false), any(IProgressMonitor.class));
    }

    /** На новом EDT состояние известно — настоящий отказ подключения обязан подниматься. */
    @Test
    public void deploy_newEdtConnectFails_throws() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(false);
        doThrow(new InfobaseSynchronizationException(Status.error("нет доступа")))
            .when(sync).connectInfobase(eq(project), eq(ref), any(IProgressMonitor.class));

        InfobaseDeployer d = new InfobaseDeployer(sync);
        try {
            d.deploy(project, ref, false, new NullProgressMonitor());
            fail("настоящий отказ подключения обязан подниматься");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("нет доступа"));
        }
    }

    @Test
    public void deploy_errorStatus_throwsToolException() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(true);
        doReturn(new Status(IStatus.ERROR, "ru.fedukhin.edt.mcp.tests", "conflict X"))
            .when(sync).updateInfobase(
                eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(false), any(IProgressMonitor.class));

        InfobaseDeployer d = new InfobaseDeployer(sync);
        try {
            d.deploy(project, ref, false, new NullProgressMonitor());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("conflict X"));
        }
    }

    @Test
    public void deploy_cancelStatus_okFalse() throws Exception {
        IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(sync.isConnected(project, ref)).thenReturn(true);
        doReturn(new Status(IStatus.CANCEL, "ru.fedukhin.edt.mcp.tests", "cancelled"))
            .when(sync).updateInfobase(
                eq(project), eq(ref), any(IInfobaseUpdateCallback.class), eq(false), any(IProgressMonitor.class));

        InfobaseDeployer d = new InfobaseDeployer(sync);
        InfobaseDeployer.DeployResult result = d.deploy(project, ref, false, new NullProgressMonitor());
        assertFalse(result.ok());
    }
}
