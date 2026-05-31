package ru.fedukhin.edt.mcp.tests.tools;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.edt.workspace.DtProjectLifecycle;

public class DtProjectLifecycleTest {

    private static DtProjectLifecycle fast(IV8ProjectManager pm) {
        // poll 1ms, register wait 200ms, stability window 200ms, drain 200ms
        return new DtProjectLifecycle(pm, 1L, 200L, 200L, 200L);
    }

    @Test
    public void awaitActivation_registeredAndStable_returnsNull() throws Exception {
        IProject p = mock(IProject.class);
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(p)).thenReturn(mock(IV8Project.class));

        assertNull(fast(pm).awaitActivation(p));
    }

    @Test
    public void awaitActivation_neverRegisters_returnsWarning() throws Exception {
        IProject p = mock(IProject.class);
        when(p.getName()).thenReturn("Stuck");
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(p)).thenReturn(null);

        String warning = fast(pm).awaitActivation(p);
        assertNotNull(warning);
        assertTrue(warning.contains("migration"));
    }

    @Test
    public void awaitActivation_registersThenDegrades_returnsWarning() throws Exception {
        // BUG-NEW-B: the project registers briefly, then a late EDT teardown
        // (pending data migration) unregisters it during the stability window.
        IProject p = mock(IProject.class);
        when(p.getName()).thenReturn("ЕСС.BUGNBТест");
        IV8Project v8 = mock(IV8Project.class);
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(p)).thenReturn(v8, v8, null);

        String warning = fast(pm).awaitActivation(p);
        assertNotNull("unstable registration must be reported", warning);
        assertTrue(warning.contains("migration"));
    }

    @Test
    public void drainAfterClose_returnsWhenUnregistered() throws Exception {
        IProject p = mock(IProject.class);
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(p)).thenReturn(mock(IV8Project.class), (IV8Project) null);

        // Must complete (and not hang) once EDT unregisters the project.
        fast(pm).drainAfterClose(p);
    }

    @Test
    public void drainAfterClose_stillRegistered_returnsAtTimeout() throws Exception {
        IProject p = mock(IProject.class);
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(p)).thenReturn(mock(IV8Project.class));

        // Must give up at the drain timeout rather than blocking forever.
        fast(pm).drainAfterClose(p);
    }
}
