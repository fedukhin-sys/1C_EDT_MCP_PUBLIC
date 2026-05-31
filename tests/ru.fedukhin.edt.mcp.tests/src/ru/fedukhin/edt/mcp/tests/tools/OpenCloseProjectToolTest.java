package ru.fedukhin.edt.mcp.tests.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.edt.workspace.CloseProjectTool;
import ru.fedukhin.edt.mcp.tools.edt.workspace.DtProjectLifecycle;
import ru.fedukhin.edt.mcp.tools.edt.workspace.OpenProjectTool;

public class OpenCloseProjectToolTest {

    /** Fast lifecycle timings so unit tests do not really sleep for seconds. */
    private static DtProjectLifecycle fastLifecycle(IV8ProjectManager pm) {
        return new DtProjectLifecycle(pm, 1L, 100L, 5L, 100L);
    }

    @Test
    public void open_closedProject_callsOpen() throws Exception {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        // First isOpen() check returns false (not yet open); after .open() it returns true.
        when(p.isOpen()).thenReturn(false).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(p);
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(p)).thenReturn(mock(IV8Project.class));

        Map<String, Object> args = new HashMap<>(); args.put("name", "P");
        Map<String, Object> result = new OpenProjectTool(() -> root, fastLifecycle(pm)).call(args);
        verify(p, times(1)).open(any(IProgressMonitor.class));
        assertEquals(Boolean.TRUE, result.get("open"));
        assertEquals("P", result.get("name"));
        assertNull("activated project must not warn", result.get("warning"));
    }

    @Test
    public void open_alreadyOpenProject_isNoOp() throws Exception {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(p);
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(p)).thenReturn(mock(IV8Project.class));

        Map<String, Object> args = new HashMap<>(); args.put("name", "P");
        Map<String, Object> result = new OpenProjectTool(() -> root, fastLifecycle(pm)).call(args);
        verify(p, never()).open(any(IProgressMonitor.class));
        assertEquals(Boolean.TRUE, result.get("open"));
    }

    @Test
    public void open_projectThatNeverActivates_returnsWarning() throws Exception {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(false).thenReturn(true);
        when(p.getName()).thenReturn("Stuck");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Stuck")).thenReturn(p);
        // DT project never registers — simulates BUG-NEW-B (pending data migration).
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(p)).thenReturn(null);

        Map<String, Object> args = new HashMap<>(); args.put("name", "Stuck");
        Map<String, Object> result = new OpenProjectTool(() -> root, fastLifecycle(pm)).call(args);
        Object warning = result.get("warning");
        assertTrue("a warning must be reported", warning instanceof String);
        assertTrue("warning mentions the migration cause",
            ((String) warning).contains("migration"));
    }

    @Test(expected = ToolException.class)
    public void open_missingProjectThrows() throws Exception {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("X")).thenReturn(p);
        Map<String, Object> args = new HashMap<>(); args.put("name", "X");
        new OpenProjectTool(() -> root, fastLifecycle(mock(IV8ProjectManager.class))).call(args);
    }

    @Test
    public void close_openProject_callsClose() throws Exception {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(true).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(p);
        // getProject() returns null (default) -> teardown drain finishes immediately.
        IV8ProjectManager pm = mock(IV8ProjectManager.class);

        Map<String, Object> args = new HashMap<>(); args.put("name", "P");
        Map<String, Object> result = new CloseProjectTool(() -> root, fastLifecycle(pm)).call(args);
        verify(p, times(1)).close(any(IProgressMonitor.class));
        assertEquals(Boolean.FALSE, result.get("open"));
    }

    @Test
    public void close_alreadyClosedProject_isNoOp() throws Exception {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(p);
        IV8ProjectManager pm = mock(IV8ProjectManager.class);

        Map<String, Object> args = new HashMap<>(); args.put("name", "P");
        Map<String, Object> result = new CloseProjectTool(() -> root, fastLifecycle(pm)).call(args);
        verify(p, never()).close(any(IProgressMonitor.class));
        assertEquals(Boolean.FALSE, result.get("open"));
    }
}
