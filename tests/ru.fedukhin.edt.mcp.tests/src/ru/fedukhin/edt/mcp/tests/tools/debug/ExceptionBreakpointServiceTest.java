package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslExceptionBreakpoint;
import ru.fedukhin.edt.mcp.tools.debug.internal.ExceptionBreakpointService;

/**
 * Тесты {@link ExceptionBreakpointService} — идемпотентность поиска уже установленного
 * catch-all и снятие breakpoint'а.
 *
 * <p>Путь создания нового breakpoint'а здесь не покрыт намеренно: он инстанцирует
 * непубличный EDT-класс {@code BslExceptionBreakpoint} рефлексией и создаёт marker на
 * реальном ресурсе workspace — это территория live-smoke, а не юнит-теста.
 */
public class ExceptionBreakpointServiceTest {

    /** Breakpoint, который одновременно IBreakpoint и IBslExceptionBreakpoint — как в EDT. */
    private static IBslExceptionBreakpoint exceptionBreakpoint(boolean catchAll) throws CoreException {
        IBslExceptionBreakpoint bp = mock(IBslExceptionBreakpoint.class,
                withSettings().extraInterfaces(IBreakpoint.class));
        when(bp.isCatchAllExceptions()).thenReturn(catchAll);
        return bp;
    }

    private static ExceptionBreakpointService serviceWith(IBreakpointManager manager) {
        return new ExceptionBreakpointService(manager, () -> mock(IWorkspaceRoot.class));
    }

    @Test
    public void installCatchAll_existingCatchAll_isReusedNotRecreated() throws Exception {
        IBslExceptionBreakpoint existing = exceptionBreakpoint(true);
        IBreakpointManager manager = mock(IBreakpointManager.class);
        when(manager.getBreakpoints()).thenReturn(new IBreakpoint[] { (IBreakpoint) existing });

        IBreakpoint result = serviceWith(manager).installCatchAll();

        assertSame("повторный debug_client не должен плодить catch-all", existing, result);
        verify(manager, never()).addBreakpoint(any());
    }

    @Test
    public void installCatchAll_ignoresNonCatchAllExceptionBreakpoints() throws Exception {
        // Точечный exception breakpoint пользователя не должен приниматься за наш catch-all.
        IBslExceptionBreakpoint narrow = exceptionBreakpoint(false);
        IBreakpointManager manager = mock(IBreakpointManager.class);
        when(manager.getBreakpoints()).thenReturn(new IBreakpoint[] { (IBreakpoint) narrow });

        try {
            IBreakpoint result = serviceWith(manager).installCatchAll();
            org.junit.Assert.assertNotSame(narrow, result);
        } catch (ru.fedukhin.edt.mcp.core.api.ToolException expected) {
            // Пойдя мимо existing, сервис ушёл в путь создания (рефлексия + marker на
            // ресурсе) и упал на моках — это и подтверждает, что narrow не был переиспользован.
        }
        verify(narrow).isCatchAllExceptions();
    }

    @Test
    public void installCatchAll_skipsBreakpointWithUnreadableMarker() throws Exception {
        // isCatchAllExceptions() читает marker; если marker уже удалён — CoreException.
        // Такой breakpoint надо пропустить, а не ронять весь debug_client.
        IBslExceptionBreakpoint broken = mock(IBslExceptionBreakpoint.class,
                withSettings().extraInterfaces(IBreakpoint.class));
        when(broken.isCatchAllExceptions())
                .thenThrow(new CoreException(new Status(IStatus.ERROR, "test", "marker gone")));
        IBreakpointManager manager = mock(IBreakpointManager.class);
        when(manager.getBreakpoints()).thenReturn(new IBreakpoint[] { (IBreakpoint) broken });

        try {
            serviceWith(manager).installCatchAll();
        } catch (ru.fedukhin.edt.mcp.core.api.ToolException expected) {
            // дошли до пути создания — значит, битый breakpoint не подобран как готовый
        }
        verify(broken).isCatchAllExceptions();
    }

    @Test
    public void remove_deletesBreakpointAndItsMarker() throws Exception {
        IBreakpointManager manager = mock(IBreakpointManager.class);
        IBreakpoint bp = mock(IBreakpoint.class);

        serviceWith(manager).remove(bp);

        verify(manager).removeBreakpoint(bp, true);
    }

    @Test
    public void remove_null_isNoOp() throws Exception {
        IBreakpointManager manager = mock(IBreakpointManager.class);

        serviceWith(manager).remove(null);

        verify(manager, never()).removeBreakpoint(any(), anyBoolean());
    }

    @Test
    public void remove_managerThrows_isSwallowed() throws Exception {
        // remove() зовётся с путей уборки (stop_debug, откат неудачного запуска) —
        // сбой удаления marker'а не должен подменять исходную ошибку.
        IBreakpointManager manager = mock(IBreakpointManager.class);
        IBreakpoint bp = mock(IBreakpoint.class);
        doThrow(new CoreException(new Status(IStatus.ERROR, "test", "boom")))
                .when(manager).removeBreakpoint(eq(bp), anyBoolean());

        serviceWith(manager).remove(bp);   // не должно бросить

        verify(manager).removeBreakpoint(bp, true);
    }
}
