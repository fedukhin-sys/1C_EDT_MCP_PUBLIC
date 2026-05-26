package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.eclipse.debug.core.ILaunch;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugEventSource;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateReader;

public class DebugSessionLaunchTest {

    private static final class NoopEventSource implements DebugEventSource {
        @Override public void addListener(org.eclipse.debug.core.IDebugEventSetListener l) {}
        @Override public void removeListener(org.eclipse.debug.core.IDebugEventSetListener l) {}
    }

    private DebugSession session(IRuntimeDebugClientTarget target, ILaunch launch, Process clientProcess) {
        return new DebugSession(UUID.randomUUID(), UUID.randomUUID(), target, launch, clientProcess,
                new NoopEventSource(), new DebugStateReader(), "DemoIB", "thin", Instant.now());
    }

    @Test
    public void terminate_terminatesLaunchAndDestroysClientProcess() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(false);
        Process client = mock(Process.class);
        when(client.isAlive()).thenReturn(true);

        session(target, launch, client).terminate();

        verify(launch).terminate();
        verify(client).destroy();
    }

    @Test
    public void terminate_alreadyTerminatedLaunch_doesNotCallTerminateAgain() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(true);
        Process client = mock(Process.class);
        when(client.isAlive()).thenReturn(false);

        session(target, launch, client).terminate();

        verify(launch, never()).terminate();
        verify(client, never()).destroy();
    }

    @Test
    public void terminate_nullLaunchAndProcess_isNoOp() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        session(target, null, null).terminate(); // must not throw
    }

    @Test
    public void findFrame_returnsFrameWithMatchingLevelFromSuspendedThread() throws Exception {
        IBslStackFrame f0 = mock(IBslStackFrame.class);
        when(f0.getLevel()).thenReturn(0);
        IBslStackFrame f1 = mock(IBslStackFrame.class);
        when(f1.getLevel()).thenReturn(1);
        IRuntimeDebugTargetThread thread = mock(IRuntimeDebugTargetThread.class);
        when(thread.isSuspended()).thenReturn(true);
        when(thread.getStackFrames()).thenReturn(new IBslStackFrame[] { f0, f1 });
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { thread });

        assertSame(f1, session(target, null, null).findFrame("1"));
    }

    @Test
    public void findFrame_unknownId_throwsToolException() {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[0]);
        try {
            session(target, null, null).findFrame("3");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("3"));
        }
    }

    @Test
    public void findFrame_suspendedThreadWithoutMatchingFrame_throwsToolException() {
        // a suspended thread WITH frames, but none at the requested level — exercises the
        // inner frame-scan loop (the empty-thread-array case above never enters it).
        IBslStackFrame f0 = mock(IBslStackFrame.class);
        when(f0.getLevel()).thenReturn(0);
        IRuntimeDebugTargetThread thread = mock(IRuntimeDebugTargetThread.class);
        when(thread.isSuspended()).thenReturn(true);
        when(thread.getStackFrames()).thenReturn(new IBslStackFrame[] { f0 });
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { thread });
        try {
            session(target, null, null).findFrame("5");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("5"));
        }
    }

    @Test
    public void findFrame_nonSuspendedThreadWithMatchingFrame_throwsToolException() {
        // the matching frame exists, but its thread is NOT suspended — findFrame must skip it.
        IBslStackFrame f1 = mock(IBslStackFrame.class);
        when(f1.getLevel()).thenReturn(1);
        IRuntimeDebugTargetThread running = mock(IRuntimeDebugTargetThread.class);
        when(running.isSuspended()).thenReturn(false);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { running });
        try {
            session(target, null, null).findFrame("1");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("1"));
        }
    }

    @Test
    public void findFrame_nonNumericId_throwsToolException() {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        try {
            session(target, null, null).findFrame("abc");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().toLowerCase().contains("invalid"));
        }
    }
}
