package ru.fedukhin.edt.mcp.tools.debug.internal;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugElement;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * One live debug session: wraps an {@link IRuntimeDebugClientTarget} and turns the
 * event-driven Eclipse Debug Model into the synchronous control verbs of Stage 3c
 * (spec §6.2).
 *
 * <p>Mechanism: a permanent {@link IDebugEventSetListener} runs for the session's
 * lifetime. Each control verb (1) arms a one-shot {@link CountDownLatch} BEFORE
 * (2) issuing the action, then (3) awaits the latch with a timeout. Arming before
 * acting closes the race where SUSPEND fires between steps 2 and 3. A
 * {@link ReentrantLock} serialises control verbs — a concurrent call on a busy
 * session fails fast with "session busy".
 *
 * <p>The {@code ILaunch} handle, breakpoint wiring and the launch path itself are
 * Plan 2; this class only needs the target + an event source + a state reader.
 */
public class DebugSession {

    /** Step granularity for {@link #step}. */
    public enum StepKind { OVER, INTO, OUT }

    @FunctionalInterface
    private interface DebugAction {
        void run() throws DebugException;
    }

    // A pause round-trip to the EDT debug server is sub-second in practice; 10s is
    // generous headroom before pause() gives up and reports the target still running.
    private static final int PAUSE_TIMEOUT_SECONDS = 10;

    private final UUID id;
    private final UUID clientSessionId;
    private final IRuntimeDebugClientTarget target;
    private final ILaunch launch;
    private final Process clientProcess;
    private final DebugEventSource events;
    private final DebugStateReader reader;
    private final String infobase;
    private final String clientType;
    private final Instant startedAt;

    private final ReentrantLock controlLock = new ReentrantLock();
    private final IDebugEventSetListener listener = this::onDebugEvents;
    /** Armed before an action, counted down by {@link #listener} on a relevant SUSPEND/TERMINATE. */
    private volatile CountDownLatch stopWaiter;
    /** Set by {@link DebugClientTool} when stopOnError=true; removed in {@link #terminate()}. */
    private volatile ExceptionBreakpointHandle exceptionBreakpoint;

    public DebugSession(UUID id, UUID clientSessionId, IRuntimeDebugClientTarget target,
                        ILaunch launch, Process clientProcess,
                        DebugEventSource events, DebugStateReader reader,
                        String infobase, String clientType, Instant startedAt) {
        this.id = id;
        this.clientSessionId = clientSessionId;
        this.target = target;
        this.launch = launch;
        this.clientProcess = clientProcess;
        this.events = events;
        this.reader = reader;
        this.infobase = infobase;
        this.clientType = clientType;
        this.startedAt = startedAt;
        events.addListener(listener);
    }

    /** Attach a per-session exception breakpoint so {@link #terminate()} can clean it up. */
    public void setExceptionBreakpoint(IBreakpoint bp, ExceptionBreakpointService service) {
        if (bp == null || service == null) return;
        this.exceptionBreakpoint = new ExceptionBreakpointHandle(bp, service);
    }

    /** Holder for the breakpoint + the service that knows how to remove it. */
    public record ExceptionBreakpointHandle(IBreakpoint breakpoint, ExceptionBreakpointService service) {}

    public UUID id() { return id; }
    public UUID clientSessionId() { return clientSessionId; }
    public String infobase() { return infobase; }
    public String clientType() { return clientType; }
    public Instant startedAt() { return startedAt; }
    public boolean isTerminated() { return target.isTerminated(); }
    /** The live debug target — needed by EvaluationService for get_variables / evaluate. */
    public IRuntimeDebugClientTarget target() { return target; }

    /** Non-blocking snapshot of the current state. */
    public DebugStateDto getState() {
        return reader.toDebugState(id, target);
    }

    /** Call stack of the given thread (top frame first), as DTOs. */
    public List<StackFrameDto> getStack(String threadId) throws ToolException {
        IRuntimeDebugTargetThread thread = findThread(threadId);
        if (!thread.isSuspended()) {
            throw new ToolException("thread '" + threadId + "' is not suspended");
        }
        IBslStackFrame[] frames = thread.getStackFrames();
        List<StackFrameDto> result = new ArrayList<>();
        if (frames != null) {
            for (IBslStackFrame frame : frames) {
                result.add(reader.toFrameDto(frame));
            }
        }
        return result;
    }

    /** Resume the target; block until the next stop or {@code timeoutSeconds} elapses. */
    public DebugStateDto resume(int timeoutSeconds) throws ToolException {
        return doControl(timeoutSeconds, target::resume);
    }

    /** Suspend the target; block (briefly) until it reports suspended. */
    public DebugStateDto pause() throws ToolException {
        // Already suspended: target.suspend() would fire no new SUSPEND event, so
        // doControl would block until timeout and wrongly report running. Just
        // return the current state.
        if (target.isSuspended()) {
            return reader.toDebugState(id, target);
        }
        return doControl(PAUSE_TIMEOUT_SECONDS, target::suspend);
    }

    /** Step the given thread; block until the step completes or {@code timeoutSeconds} elapses. */
    public DebugStateDto step(String threadId, StepKind kind, int timeoutSeconds) throws ToolException {
        IRuntimeDebugTargetThread thread = findThread(threadId);
        // Best-effort check: the thread could in principle be resumed by another actor
        // between here and the step dispatch inside doControl. In practice this session
        // is the sole driver of its target, so the window is not exploitable.
        if (!thread.isSuspended()) {
            throw new ToolException("thread '" + threadId + "' is not suspended");
        }
        return doControl(timeoutSeconds, () -> {
            switch (kind) {
                case OVER -> thread.stepOver();
                case INTO -> thread.stepInto();
                case OUT  -> thread.stepReturn();
            }
        });
    }

    /** Detach the event listener — call on stop_debug / when the session is dropped. */
    public void dispose() {
        events.removeListener(listener);
    }

    /**
     * Tear the session down. Without the explicit target disconnect + launchManager removal
     * EDT held onto the previous {@link IRuntimeDebugClientTarget} after {@code launch.terminate()}
     * and the next {@code debug_client} for the same infobase failed with "Попытка повторного
     * соединения с сервером отладки по адресу http://&lt;host&gt;:&lt;port&gt;/" (live smoke
     * 2026-05-18). Fix: disconnect/terminate the target first (severs dbgs connection), then
     * terminate the launch (kills dbgs + clients via EDT IProcessListener), then remove the
     * launch from {@link ILaunchManager} so a subsequent {@code createLocal} for the same
     * infobase starts a fresh target on a new port instead of reattaching.
     *
     * <p>The event listener is detached by {@link DebugSessionRegistry#remove} (which calls
     * {@link #dispose()}), so this method does not — call {@code registry.remove(id)} first,
     * then {@code terminate()}.
     *
     * <p>Best-effort and null-safe: every step skips on null/already-terminated.
     */
    public void terminate() {
        ExceptionBreakpointHandle bp = exceptionBreakpoint;
        if (bp != null) {
            bp.service().remove(bp.breakpoint());
            exceptionBreakpoint = null;
        }
        if (target != null && !target.isTerminated()) {
            try {
                if (target.canDisconnect()) {
                    target.disconnect();
                } else if (target.canTerminate()) {
                    target.terminate();
                }
            } catch (DebugException e) {
                // best-effort — fall through to launch.terminate() below
            }
        }
        if (launch != null) {
            try {
                if (!launch.isTerminated()) {
                    launch.terminate();
                }
            } catch (DebugException e) {
                // best-effort — the target may already be gone
            }
            try {
                ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
                if (mgr != null) {
                    mgr.removeLaunch(launch);
                }
            } catch (RuntimeException e) {
                // best-effort — EDT may have already cleared it
            }
        }
        if (clientProcess != null && clientProcess.isAlive()) {
            clientProcess.destroy();
        }
    }

    private DebugStateDto doControl(int timeoutSeconds, DebugAction action) throws ToolException {
        if (!controlLock.tryLock()) {
            throw new ToolException("session '" + id + "' is busy with another debug operation");
        }
        try {
            if (target.isTerminated()) {
                return reader.terminated(id);
            }
            CountDownLatch waiter = new CountDownLatch(1);
            stopWaiter = waiter;                       // arm BEFORE acting — closes the race
            boolean signalled;
            try {
                action.run();
                signalled = waiter.await(timeoutSeconds, TimeUnit.SECONDS);
            } catch (DebugException e) {
                throw new ToolException("debug control failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolException("interrupted waiting for debug stop");
            } finally {
                stopWaiter = null;
            }
            if (!signalled) {
                return reader.running(id, true);       // timed out — action stays in effect
            }
            if (target.isTerminated()) {
                return reader.terminated(id);
            }
            return reader.toDebugState(id, target);    // suspended
        } finally {
            controlLock.unlock();
        }
    }

    private void onDebugEvents(DebugEvent[] eventsBatch) {
        for (DebugEvent e : eventsBatch) {
            int kind = e.getKind();
            if (kind != DebugEvent.SUSPEND && kind != DebugEvent.TERMINATE) {
                continue;
            }
            if (!isOurEvent(e)) {
                continue;
            }
            CountDownLatch waiter = stopWaiter;
            if (waiter != null) {
                waiter.countDown();
            }
        }
    }

    private boolean isOurEvent(DebugEvent e) {
        Object src = e.getSource();
        if (src == target) {
            return true;
        }
        if (src instanceof IDebugElement de) {
            return de.getDebugTarget() == target;
        }
        return false;
    }

    /**
     * The stack frame with the given id (its {@code IBslStackFrame.getLevel()}), searched across
     * every suspended thread. With BSL client debug the suspended set is effectively a single
     * thread, so a level uniquely identifies a frame; if several suspended threads ever shared a
     * level, the first match wins. Throws if the id is non-numeric or no suspended frame has it.
     */
    public IBslStackFrame findFrame(String frameId) throws ToolException {
        int level;
        try {
            level = Integer.parseInt(frameId);
        } catch (NumberFormatException e) {
            throw new ToolException("invalid frameId: '" + frameId + "'");
        }
        IRuntimeDebugTargetThread[] threads = target.getThreads();
        if (threads != null) {
            for (IRuntimeDebugTargetThread t : threads) {
                if (!t.isSuspended()) {
                    continue;
                }
                IBslStackFrame[] frames = t.getStackFrames();
                if (frames != null) {
                    for (IBslStackFrame f : frames) {
                        if (f.getLevel() == level) {
                            return f;
                        }
                    }
                }
            }
        }
        throw new ToolException("frame '" + frameId + "' not found in session '" + id
                + "' (no suspended thread has it)");
    }

    private IRuntimeDebugTargetThread findThread(String threadId) throws ToolException {
        // threadId is the stack-index string assigned by DebugStateReader (e.g. "0", "1").
        int index;
        try {
            index = Integer.parseInt(threadId);
        } catch (NumberFormatException e) {
            throw new ToolException("invalid threadId: '" + threadId + "'");
        }
        IRuntimeDebugTargetThread[] threads = target.getThreads();
        if (threads == null || index < 0 || index >= threads.length) {
            throw new ToolException("thread '" + threadId + "' not found in session '" + id + "'");
        }
        return threads[index];
    }
}
