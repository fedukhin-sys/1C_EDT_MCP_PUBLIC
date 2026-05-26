package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugEventSource;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateDto;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateReader;

public class DebugSessionTest {

    /** A DebugEventSource that captures the listener so the test can fire events at it. */
    private static final class FakeEventSource implements DebugEventSource {
        private IDebugEventSetListener listener;
        @Override public void addListener(IDebugEventSetListener l) { this.listener = l; }
        @Override public void removeListener(IDebugEventSetListener l) { this.listener = null; }
        void fire(DebugEvent e) { listener.handleDebugEvents(new DebugEvent[] { e }); }
    }

    private DebugSession newSession(IRuntimeDebugClientTarget target, FakeEventSource events) {
        return new DebugSession(UUID.randomUUID(), UUID.randomUUID(), target, null, null, events,
                new DebugStateReader(), "DemoIB", "thin", Instant.now());
    }

    @Test
    public void resume_suspendEventReleasesWaiter_returnsSuspended() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.isTerminated()).thenReturn(false);
        IRuntimeDebugTargetThread suspended = mock(IRuntimeDebugTargetThread.class);
        when(suspended.isSuspended()).thenReturn(true);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { suspended });
        FakeEventSource events = new FakeEventSource();
        DebugSession session = newSession(target, events);

        // resume() blocks; fire SUSPEND from another thread after a beat
        AtomicReference<DebugStateDto> result = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { result.set(session.resume(5)); } catch (ToolException e) { fail(e.getMessage()); }
        });
        caller.start();
        Thread.sleep(100);
        events.fire(suspendEventFrom(target));
        caller.join(2000);

        assertEquals(DebugStateDto.SUSPENDED, result.get().state());
        assertFalse(result.get().timedOut());
    }

    @Test
    public void resume_noEvent_returnsTimedOutRunning() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.isTerminated()).thenReturn(false);
        DebugSession session = newSession(target, new FakeEventSource());

        DebugStateDto s = session.resume(1); // 1s timeout, no event fired

        assertEquals(DebugStateDto.RUNNING, s.state());
        assertTrue(s.timedOut());
    }

    @Test
    public void resume_terminateEvent_returnsTerminated() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        // terminated only AFTER the TERMINATE event is observed — flag-based, not call-count-based
        java.util.concurrent.atomic.AtomicBoolean terminated = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(target.isTerminated()).thenAnswer(inv -> terminated.get());
        FakeEventSource events = new FakeEventSource();
        DebugSession session = newSession(target, events);

        AtomicReference<DebugStateDto> result = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { result.set(session.resume(5)); } catch (ToolException e) { fail(e.getMessage()); }
        });
        caller.start();
        Thread.sleep(100);
        terminated.set(true);
        DebugEvent te = new DebugEvent(target, DebugEvent.TERMINATE);
        events.fire(te);
        caller.join(2000);

        assertEquals(DebugStateDto.TERMINATED, result.get().state());
    }

    @Test
    public void resume_eventForOtherTarget_isIgnored() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.isTerminated()).thenReturn(false);
        FakeEventSource events = new FakeEventSource();
        DebugSession session = newSession(target, events);

        // SUSPEND from a DIFFERENT target must not release our waiter
        IRuntimeDebugClientTarget other = mock(IRuntimeDebugClientTarget.class);
        AtomicReference<DebugStateDto> result = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { result.set(session.resume(1)); } catch (ToolException e) { fail(e.getMessage()); }
        });
        caller.start();
        Thread.sleep(100);
        events.fire(suspendEventFrom(other));
        caller.join(3000);

        assertEquals(DebugStateDto.RUNNING, result.get().state()); // timed out, not suspended
        assertTrue(result.get().timedOut());
    }

    @Test
    public void control_concurrentCallOnBusySession_throwsSessionBusy() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.isTerminated()).thenReturn(false);
        DebugSession session = newSession(target, new FakeEventSource());

        // target.resume() runs only after doControl has acquired controlLock and armed
        // the waiter; signalling lockHeld there proves the session is genuinely busy.
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            lockHeld.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(target).resume();

        Thread first = new Thread(() -> {
            try { session.resume(5); } catch (ToolException ignored) { }
        });
        first.start();
        assertTrue("first call should have acquired the control lock", lockHeld.await(2, TimeUnit.SECONDS));

        try {
            session.resume(1);
            fail("expected ToolException: session busy");
        } catch (ToolException e) {
            assertTrue(e.getMessage().toLowerCase().contains("busy"));
        } finally {
            release.countDown();   // let the first thread finish
            first.join(3000);
        }
    }

    @Test
    public void resume_suspendDuringAction_noRaceLost() throws Exception {
        // SUSPEND fires synchronously inside target.resume() — the waiter is armed
        // BEFORE the action, so the latch is already counted down when await() runs.
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class, RETURNS_DEEP_STUBS);
        when(target.isTerminated()).thenReturn(false);
        IRuntimeDebugTargetThread suspended = mock(IRuntimeDebugTargetThread.class);
        when(suspended.isSuspended()).thenReturn(true);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { suspended });
        FakeEventSource events = new FakeEventSource();
        DebugSession session = newSession(target, events);
        doAnswer(inv -> { events.fire(suspendEventFrom(target)); return null; })
                .when(target).resume();

        DebugStateDto s = session.resume(2);

        assertEquals(DebugStateDto.SUSPENDED, s.state());
    }

    @Test
    public void pause_alreadySuspendedTarget_returnsStateWithoutCallingSuspend() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.isSuspended()).thenReturn(true);
        IRuntimeDebugTargetThread suspended = mock(IRuntimeDebugTargetThread.class);
        when(suspended.isSuspended()).thenReturn(true);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { suspended });
        DebugSession session = newSession(target, new FakeEventSource());

        DebugStateDto s = session.pause();

        assertEquals(DebugStateDto.SUSPENDED, s.state());
        verify(target, never()).suspend();
    }

    private static DebugEvent suspendEventFrom(IRuntimeDebugClientTarget target) {
        // event source is the target itself; getDebugTarget() on an IDebugTarget returns itself
        when(target.getDebugTarget()).thenReturn(target);
        return new DebugEvent(target, DebugEvent.SUSPEND, DebugEvent.BREAKPOINT);
    }
}
