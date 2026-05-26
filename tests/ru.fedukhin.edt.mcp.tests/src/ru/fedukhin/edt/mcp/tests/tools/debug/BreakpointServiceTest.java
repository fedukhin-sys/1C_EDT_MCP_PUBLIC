package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointFactory;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslLineBreakpoint;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointInfo;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointService;

public class BreakpointServiceTest {

    private final IBslBreakpointFactory factory = mock(IBslBreakpointFactory.class);
    private final IBreakpointManager manager = mock(IBreakpointManager.class);
    private final IWorkspaceRoot root = mock(IWorkspaceRoot.class);
    private final BreakpointService service =
            new BreakpointService(factory, manager, () -> root);

    private IFile mockFile(String projectName, String path, boolean exists) {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(projectName);
        when(project.exists()).thenReturn(true);
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(exists);
        when(file.getProject()).thenReturn(project);
        when(file.getProjectRelativePath()).thenReturn(org.eclipse.core.runtime.Path.fromPortableString(path));
        when(root.getProject(projectName)).thenReturn(project);
        when(project.getFile(path)).thenReturn(file);
        return file;
    }

    private IBslLineBreakpoint mockBreakpoint(long markerId, IFile file,
                                              int line, String condition) throws CoreException {
        IMarker marker = mock(IMarker.class);
        when(marker.getId()).thenReturn(markerId);
        when(marker.getResource()).thenReturn(file);
        IBslLineBreakpoint bp = mock(IBslLineBreakpoint.class);
        when(bp.getMarker()).thenReturn(marker);
        when(bp.getLineNumber()).thenReturn(line);
        when(bp.getCondition()).thenReturn(condition);
        return bp;
    }

    @Test
    public void setBreakpoint_createsViaFactory_returnsInfo() throws Exception {
        IFile file = mockFile("DemoIB", "src/M.bsl", true);
        IBslLineBreakpoint bp = mockBreakpoint(101L, file, 42, null);
        when(factory.createLineBreakpoint(file, 42)).thenReturn(bp);

        BreakpointInfo info = service.setBreakpoint("DemoIB", "src/M.bsl", 42, null);

        assertEquals("101", info.id());
        assertEquals("DemoIB", info.project());
        assertEquals("src/M.bsl", info.path());
        assertEquals(42, info.line());
        assertNull(info.condition());
        verify(factory).createLineBreakpoint(file, 42);
        verify(manager).addBreakpoint(bp); // factory does not auto-register — service must
    }

    @Test
    public void setBreakpoint_withCondition_setsConditionOnBreakpoint() throws Exception {
        IFile file = mockFile("DemoIB", "src/M.bsl", true);
        IBslLineBreakpoint bp = mockBreakpoint(102L, file, 10, "Счётчик > 5");
        when(factory.createLineBreakpoint(file, 10)).thenReturn(bp);

        BreakpointInfo info = service.setBreakpoint("DemoIB", "src/M.bsl", 10, "Счётчик > 5");

        verify(bp).setCondition("Счётчик > 5");
        assertEquals("Счётчик > 5", info.condition());
        verify(manager).addBreakpoint(bp);
    }

    @Test
    public void setBreakpoint_fileDoesNotExist_throwsToolException() {
        mockFile("DemoIB", "src/Missing.bsl", false);
        try {
            service.setBreakpoint("DemoIB", "src/Missing.bsl", 1, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("src/Missing.bsl"));
        }
    }

    @Test
    public void list_returnsOnlyBslLineBreakpoints() throws Exception {
        IFile fileA = mockFile("DemoIB", "src/A.bsl", true);
        IBslLineBreakpoint bsl = mockBreakpoint(201L, fileA, 5, null);
        IBreakpoint other = mock(IBreakpoint.class); // a non-BSL breakpoint
        when(manager.getBreakpoints()).thenReturn(new IBreakpoint[] { bsl, other });

        List<BreakpointInfo> all = service.list();

        assertEquals(1, all.size());
        assertEquals("201", all.get(0).id());
    }

    @Test
    public void remove_byId_deletesMatchingBreakpoint() throws Exception {
        IFile fileA = mockFile("DemoIB", "src/A.bsl", true);
        IBslLineBreakpoint bp = mockBreakpoint(301L, fileA, 5, null);
        when(manager.getBreakpoints()).thenReturn(new IBreakpoint[] { bp });

        service.remove("301");

        verify(manager).removeBreakpoint(bp, true);
    }

    @Test
    public void remove_unknownId_isNoOpSuccess() throws Exception {
        when(manager.getBreakpoints()).thenReturn(new IBreakpoint[0]);
        service.remove("999"); // must not throw
        verify(manager, never()).removeBreakpoint(any(), eq(true));
    }

    // I1 — project-not-found guard
    @Test
    public void setBreakpoint_projectDoesNotExist_throwsToolException() {
        IProject ghost = mock(IProject.class);
        when(ghost.exists()).thenReturn(false);
        when(root.getProject("Ghost")).thenReturn(ghost);

        try {
            service.setBreakpoint("Ghost", "src/M.bsl", 1, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("project"));
        }
    }

    // I2 — best-effort list: unreadable breakpoint is skipped
    @Test
    public void list_skipsUnreadableBreakpoint() throws CoreException {
        IFile fileA = mockFile("DemoIB", "src/A.bsl", true);
        IBslLineBreakpoint good = mockBreakpoint(201L, fileA, 5, null);

        // Bad breakpoint: marker exists but getLineNumber() throws
        IMarker badMarker = mock(IMarker.class);
        when(badMarker.getId()).thenReturn(202L);
        when(badMarker.getResource()).thenReturn(fileA);
        IBslLineBreakpoint bad = mock(IBslLineBreakpoint.class);
        when(bad.getMarker()).thenReturn(badMarker);
        when(bad.getLineNumber()).thenThrow(new org.eclipse.core.runtime.CoreException(
                new org.eclipse.core.runtime.Status(
                        org.eclipse.core.runtime.IStatus.ERROR, "test", "marker gone")));

        when(manager.getBreakpoints()).thenReturn(new IBreakpoint[] { good, bad });

        List<BreakpointInfo> result = service.list();

        assertEquals(1, result.size());
        assertEquals("201", result.get(0).id());
    }

    // M3 — empty condition is treated as unconditional
    @Test
    public void setBreakpoint_emptyCondition_treatedAsUnconditional() throws Exception {
        IFile file = mockFile("DemoIB", "src/M.bsl", true);
        IBslLineBreakpoint bp = mockBreakpoint(103L, file, 7, null);
        when(factory.createLineBreakpoint(file, 7)).thenReturn(bp);

        service.setBreakpoint("DemoIB", "src/M.bsl", 7, "");

        verify(bp, never()).setCondition(any());
        verify(manager).addBreakpoint(bp);
    }
}
