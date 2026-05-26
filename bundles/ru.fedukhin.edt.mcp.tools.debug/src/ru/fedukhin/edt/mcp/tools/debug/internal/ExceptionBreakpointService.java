package ru.fedukhin.edt.mcp.tools.debug.internal;

import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslExceptionBreakpoint;
import jakarta.inject.Inject;
import java.lang.reflect.Constructor;
import java.util.function.Supplier;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Creates and registers a catch-all BSL exception breakpoint so the debug-server suspends
 * on any runtime BSL exception, not only at line breakpoints. EDT exposes {@link
 * IBslExceptionBreakpoint} but keeps the impl class
 * {@code com._1c.g5.v8.dt.internal.debug.core.model.breakpoints.BslExceptionBreakpoint}
 * in a non-exported package, so we instantiate it via reflection through the
 * interface's bundle classloader (the {@code Require-Bundle: com._1c.g5.v8.dt.debug.core}
 * declaration gives us visibility, only the package-export restriction blocks a direct
 * compile-time reference).
 */
public class ExceptionBreakpointService {

    private static final String IMPL_CLASS =
            "com._1c.g5.v8.dt.internal.debug.core.model.breakpoints.BslExceptionBreakpoint";

    private final IBreakpointManager manager;
    private final Supplier<IWorkspaceRoot> rootSupplier;

    @Inject
    public ExceptionBreakpointService(IBreakpointManager manager) {
        this(manager, () -> ResourcesPlugin.getWorkspace().getRoot());
    }

    /** Test seam. */
    public ExceptionBreakpointService(IBreakpointManager manager,
                                      Supplier<IWorkspaceRoot> rootSupplier) {
        this.manager = manager;
        this.rootSupplier = rootSupplier;
    }

    /**
     * Create a catch-all exception breakpoint and register it with the workspace breakpoint
     * manager. Idempotent: if a catch-all breakpoint already exists it is returned as-is.
     */
    public IBreakpoint installCatchAll() throws ToolException {
        IBreakpoint existing = findCatchAll();
        if (existing != null) {
            return existing;
        }
        IResource resource = rootSupplier.get();
        try {
            Class<?> clazz = Class.forName(IMPL_CLASS, true,
                    IBslExceptionBreakpoint.class.getClassLoader());
            Constructor<?> ctor = clazz.getConstructor(IResource.class);
            Object bp = ctor.newInstance(resource);
            if (!(bp instanceof IBreakpoint ibp) || !(bp instanceof IBslExceptionBreakpoint ebp)) {
                throw new ToolException("BslExceptionBreakpoint did not implement expected interfaces");
            }
            ebp.setCatchAllExceptions(true);
            // catchAll=true + exceptionMessage=ALL_EXCEPTIONS — две независимые оси фильтра
            // в IBslExceptionBreakpoint; без второй debug-server не суспендил на runtime-
            // ошибках «Поле объекта не обнаружено» (smoke 2026-05-18).
            ebp.setExceptionMessage(IBslExceptionBreakpoint.ALL_EXCEPTIONS);
            ibp.setEnabled(true);
            manager.addBreakpoint(ibp);
            return ibp;
        } catch (ReflectiveOperationException | RuntimeException | CoreException e) {
            throw new ToolException("failed to install catch-all exception breakpoint: "
                    + e.getMessage(), e);
        }
    }

    /** Remove a previously-installed exception breakpoint (idempotent). */
    public void remove(IBreakpoint bp) {
        if (bp == null) return;
        try {
            manager.removeBreakpoint(bp, /*delete marker*/ true);
        } catch (CoreException e) {
            // best-effort — manager will leak the entry if marker delete fails, but
            // EDT cleans up next workspace cycle
        }
    }

    private IBreakpoint findCatchAll() {
        for (IBreakpoint bp : manager.getBreakpoints()) {
            if (bp instanceof IBslExceptionBreakpoint ebp) {
                try {
                    if (ebp.isCatchAllExceptions()) {
                        return bp;
                    }
                } catch (CoreException e) {
                    // skip — marker unreadable
                }
            }
        }
        return null;
    }

    /** Convenience for callers that already have a {@link DebugPlugin}-backed manager. */
    public static IBreakpointManager defaultManager() {
        return DebugPlugin.getDefault().getBreakpointManager();
    }
}
