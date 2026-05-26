package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugEventSource;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateReader;
import ru.fedukhin.edt.mcp.tools.debug.internal.StackFrameDto;

public class DebugSessionGetStackTest {

    private static final class NoopEventSource implements DebugEventSource {
        @Override public void addListener(org.eclipse.debug.core.IDebugEventSetListener l) {}
        @Override public void removeListener(org.eclipse.debug.core.IDebugEventSetListener l) {}
    }

    private DebugSession session(IRuntimeDebugClientTarget target) {
        return new DebugSession(UUID.randomUUID(), UUID.randomUUID(), target, null, null,
                new NoopEventSource(), new DebugStateReader(), "DemoIB", "thin", Instant.now());
    }

    @Test
    public void getStack_returnsFramesOfThread() throws Exception {
        IBslStackFrame f0 = mock(IBslStackFrame.class);
        when(f0.getLevel()).thenReturn(0);
        when(f0.getLineNumber()).thenReturn(10);
        when(f0.getName()).thenReturn("Метод0");
        when(f0.getReference()).thenReturn(null);
        when(f0.getSource()).thenReturn(null);
        IBslStackFrame f1 = mock(IBslStackFrame.class);
        when(f1.getLevel()).thenReturn(1);
        when(f1.getLineNumber()).thenReturn(20);
        when(f1.getName()).thenReturn("Метод1");
        when(f1.getReference()).thenReturn(null);
        when(f1.getSource()).thenReturn(null);

        IRuntimeDebugTargetThread thread = mock(IRuntimeDebugTargetThread.class);
        when(thread.isSuspended()).thenReturn(true);
        when(thread.getStackFrames()).thenReturn(new IBslStackFrame[] { f0, f1 });
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { thread });

        List<StackFrameDto> stack = session(target).getStack("0");

        assertEquals(2, stack.size());
        assertEquals("0", stack.get(0).frameId());
        assertEquals(10, stack.get(0).line());
        assertEquals("1", stack.get(1).frameId());
        assertEquals("Метод1", stack.get(1).method());
    }

    @Test
    public void getStack_nullFrames_returnsEmptyList() throws Exception {
        IRuntimeDebugTargetThread thread = mock(IRuntimeDebugTargetThread.class);
        when(thread.isSuspended()).thenReturn(true);
        when(thread.getStackFrames()).thenReturn(null);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { thread });

        List<StackFrameDto> stack = session(target).getStack("0");

        assertEquals(0, stack.size());
    }

    @Test
    public void getStack_unknownThreadId_throwsToolException() {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[0]);
        try {
            session(target).getStack("0");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertEquals(true, e.getMessage().contains("0"));
        }
    }

    @Test
    public void getStack_throwsWhenThreadNotSuspended() {
        IRuntimeDebugTargetThread thread = mock(IRuntimeDebugTargetThread.class);
        when(thread.isSuspended()).thenReturn(false);
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { thread });
        try {
            session(target).getStack("0");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("0"));
            assertTrue(e.getMessage().contains("not suspended"));
        }
    }
}
