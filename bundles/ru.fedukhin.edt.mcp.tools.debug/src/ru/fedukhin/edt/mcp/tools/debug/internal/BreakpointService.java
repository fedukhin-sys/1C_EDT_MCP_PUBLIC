package ru.fedukhin.edt.mcp.tools.debug.internal;

import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointFactory;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslLineBreakpoint;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * CRUD of BSL line breakpoints. Wraps {@link IBslBreakpointFactory} (creates the
 * breakpoint + its workspace marker) and {@link IBreakpointManager} (the
 * workspace-global breakpoint set). Breakpoints are addressed by their marker's
 * stable workspace id ({@code IMarker.getId()}), rendered as a string.
 *
 * <p>The {@code Supplier<IWorkspaceRoot>} seam mirrors {@code tools.bsl}: the
 * {@code @Inject} constructor hardcodes the real workspace root; the second
 * public constructor lets tests inject a fake.
 */
public class BreakpointService {

    private final IBslBreakpointFactory factory;
    private final IBreakpointManager manager;
    private final Supplier<IWorkspaceRoot> rootSupplier;

    @Inject
    public BreakpointService(IBslBreakpointFactory factory, IBreakpointManager manager) {
        this(factory, manager, () -> ResourcesPlugin.getWorkspace().getRoot());
    }

    /** Visible for tests — inject a fake workspace root. */
    public BreakpointService(IBslBreakpointFactory factory, IBreakpointManager manager,
                             Supplier<IWorkspaceRoot> rootSupplier) {
        this.factory = factory;
        this.manager = manager;
        this.rootSupplier = rootSupplier;
    }

    /** Create a line breakpoint; {@code condition} may be null or empty for an unconditional one. */
    public BreakpointInfo setBreakpoint(String project, String path, int line, String condition)
            throws ToolException {
        IProject projectHandle = rootSupplier.get().getProject(project);
        if (!projectHandle.exists()) {
            throw new ToolException("project '" + project + "' not found");
        }
        IFile file = projectHandle.getFile(path);
        if (!file.exists()) {
            throw new ToolException("module not found: '" + project + "/" + path + "'");
        }
        IBslLineBreakpoint bp;
        try {
            // IBslBreakpointFactory creates the breakpoint + its workspace marker but does
            // NOT register it with the IBreakpointManager — the caller must (confirmed by
            // the Plan 2 Task 1 spike, see 2026-05-14-spike-stage-3c-debug.md "Plan 3 inputs").
            bp = factory.createLineBreakpoint(file, line);
            if (condition != null && !condition.isEmpty()) {
                bp.setCondition(condition);
            }
            manager.addBreakpoint(bp);
        } catch (CoreException e) {
            throw new ToolException("failed to set breakpoint at " + project + "/" + path
                    + ":" + line + ": " + e.getMessage(), e);
        }
        return toInfo(bp);
    }

    /** All BSL line breakpoints currently registered in the workspace. */
    public List<BreakpointInfo> list() {
        List<BreakpointInfo> result = new ArrayList<>();
        for (IBreakpoint bp : manager.getBreakpoints()) {
            if (bp instanceof IBslLineBreakpoint line) {
                try {
                    result.add(toInfo(line));
                } catch (ToolException e) {
                    // Best-effort: skip a breakpoint whose marker is unreadable rather than
                    // failing the whole list — consistent with the project's other list
                    // operations (e.g. ClientProcessRegistry.list()).
                }
            }
        }
        return result;
    }

    /** Remove a breakpoint by its marker id; unknown id is a no-op success (idempotent). */
    public void remove(String breakpointId) throws ToolException {
        for (IBreakpoint bp : manager.getBreakpoints()) {
            if (bp instanceof IBslLineBreakpoint line && breakpointId.equals(markerId(line))) {
                try {
                    manager.removeBreakpoint(line, true);
                } catch (CoreException e) {
                    throw new ToolException("failed to remove breakpoint '" + breakpointId
                            + "': " + e.getMessage(), e);
                }
                return;
            }
        }
        // unknown id — no-op success
    }

    private BreakpointInfo toInfo(IBslLineBreakpoint bp) throws ToolException {
        try {
            IResource resource = bp.getMarker().getResource();
            String project = resource.getProject() == null ? null : resource.getProject().getName();
            String path = resource.getProjectRelativePath() == null
                    ? null : resource.getProjectRelativePath().toPortableString();
            return new BreakpointInfo(markerId(bp), project, path, bp.getLineNumber(), bp.getCondition());
        } catch (CoreException e) {
            throw new ToolException("failed to read breakpoint: " + e.getMessage(), e);
        }
    }

    private static String markerId(IBslLineBreakpoint bp) {
        return String.valueOf(bp.getMarker().getId());
    }
}
