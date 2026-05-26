package ru.fedukhin.edt.mcp.tests.tools;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.edt.workspace.CloseProjectTool;
import ru.fedukhin.edt.mcp.tools.edt.workspace.OpenProjectTool;

public class OpenCloseProjectToolTest {

    @Test
    public void open_closedProject_callsOpen() throws Exception {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        // First isOpen() check returns false (not yet open); after .open() it returns true.
        when(p.isOpen()).thenReturn(false).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(p);

        Map<String, Object> args = new HashMap<>(); args.put("name", "P");
        Map<String, Object> result = new OpenProjectTool(() -> root).call(args);
        verify(p, times(1)).open(any(IProgressMonitor.class));
        assertEquals(Boolean.TRUE, result.get("open"));
        assertEquals("P", result.get("name"));
    }

    @Test
    public void open_alreadyOpenProject_isNoOp() throws Exception {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(p);

        Map<String, Object> args = new HashMap<>(); args.put("name", "P");
        Map<String, Object> result = new OpenProjectTool(() -> root).call(args);
        verify(p, never()).open(any(IProgressMonitor.class));
        assertEquals(Boolean.TRUE, result.get("open"));
    }

    @Test(expected = ToolException.class)
    public void open_missingProjectThrows() throws Exception {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("X")).thenReturn(p);
        Map<String, Object> args = new HashMap<>(); args.put("name", "X");
        new OpenProjectTool(() -> root).call(args);
    }

    @Test
    public void close_openProject_callsClose() throws Exception {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(true).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(p);

        Map<String, Object> args = new HashMap<>(); args.put("name", "P");
        Map<String, Object> result = new CloseProjectTool(() -> root).call(args);
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

        Map<String, Object> args = new HashMap<>(); args.put("name", "P");
        Map<String, Object> result = new CloseProjectTool(() -> root).call(args);
        verify(p, never()).close(any(IProgressMonitor.class));
        assertEquals(Boolean.FALSE, result.get("open"));
    }
}
