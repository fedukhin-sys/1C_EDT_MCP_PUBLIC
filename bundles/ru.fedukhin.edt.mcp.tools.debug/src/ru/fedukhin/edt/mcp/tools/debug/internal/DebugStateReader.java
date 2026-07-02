package ru.fedukhin.edt.mcp.tools.debug.internal;

import com._1c.g5.v8.dt.debug.core.model.BslModuleReference;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;
import java.util.UUID;
import org.eclipse.debug.core.DebugException;
import org.eclipse.emf.common.util.URI;

/**
 * Pure conversion of Eclipse / EDT debug-model objects into MCP DTO records.
 * No I/O, no debug-protocol calls — every method reads only already-resolved
 * getters, so the whole class is unit-testable with Mockito.
 *
 * <p>Triggering lazy evaluation of {@link IBslVariable}/{@link IBslValue}
 * (calling {@code evaluate()} and waiting) is NOT done here — that belongs to
 * the live-session code in Plan 2; this reader reflects whatever state the
 * objects are already in.
 */
public class DebugStateReader {

    /** Build a state snapshot from a live target: terminated / running / suspended. */
    public DebugStateDto toDebugState(UUID sessionId, IRuntimeDebugClientTarget target) {
        if (target.isTerminated()) {
            return terminated(sessionId);
        }
        IRuntimeDebugTargetThread[] threads = target.getThreads();
        if (threads != null) {
            for (int i = 0; i < threads.length; i++) {
                IRuntimeDebugTargetThread t = threads[i];
                if (t.isSuspended()) {
                    return suspendedAt(sessionId, String.valueOf(i), t);
                }
            }
        }
        return running(sessionId, false);
    }

    public DebugStateDto running(UUID sessionId, boolean timedOut) {
        return new DebugStateDto(sessionId.toString(), DebugStateDto.RUNNING, timedOut, null, null);
    }

    public DebugStateDto terminated(UUID sessionId) {
        return new DebugStateDto(sessionId.toString(), DebugStateDto.TERMINATED, false, null, null);
    }

    private DebugStateDto suspendedAt(UUID sessionId, String threadId, IRuntimeDebugTargetThread t) {
        ThreadRef thread = new ThreadRef(threadId, safeThreadName(t));
        SourceLocation loc = null;
        try {
            IBslStackFrame top = t.getTopStackFrame();
            if (top != null) {
                StackFrameDto f = toFrameDto(top);
                loc = new SourceLocation(f.project(), f.path(), f.line(), f.method());
            }
        } catch (DebugException e) {
            // top frame unavailable — report suspended without a location
        }
        return new DebugStateDto(sessionId.toString(), DebugStateDto.SUSPENDED, false, thread, loc);
    }

    /** Convert a stack frame. {@code frameId} is the frame's stack level. */
    public StackFrameDto toFrameDto(IBslStackFrame frame) {
        String project = null;
        BslModuleReference ref = frame.getReference();
        if (ref != null && ref.getProject() != null) {
            project = ref.getProject().getName();
        }
        String path = modulePath(frame.getSource());
        int line = safeLine(frame);
        String method = safeFrameName(frame);
        return new StackFrameDto(String.valueOf(frame.getLevel()), project, path, line, method);
    }

    /** Convert a variable. {@code value} is the value's detail string (empty if not yet evaluated). */
    public VariableDto toVariableDto(IBslVariable variable) {
        String type = null;
        String value = "";
        IBslValue v = variable.getValue();
        if (v != null) {
            type = v.getValueTypeName();
            try {
                value = v.getDetailString();
            } catch (Exception e) {
                // catch Exception (не DebugException): в EDT 2026.x getDetailString больше не
                // объявляет throws DebugException — узкий catch стал бы «unreachable». Exception
                // достижим на обеих версиях и одинаково даёт fallback.
                value = "";
            }
            if (value == null) {
                value = "";
            }
        }
        return new VariableDto(variable.getName(), type, value);
    }

    /**
     * Project-relative module path from an EMF source URI.
     * A {@code platform:/resource/Project/path} URI yields {@code path} (the
     * leading {@code /Project/} segment is stripped); anything else falls back
     * to the URI string, or {@code null} when there is no source.
     */
    private static String modulePath(URI source) {
        if (source == null) {
            return null;
        }
        if (source.isPlatformResource()) {
            String full = source.toPlatformString(true); // "/Project/src/.../M.bsl"
            if (full != null && full.startsWith("/")) {
                int slash = full.indexOf('/', 1);
                return slash >= 0 ? full.substring(slash + 1) : full.substring(1);
            }
        }
        return source.toString();
    }

    private static int safeLine(IBslStackFrame frame) {
        try {
            return frame.getLineNumber();
        } catch (Exception e) {
            // См. toVariableDto: getLineNumber в EDT 2026.x больше не throws DebugException.
            return -1;
        }
    }

    private static String safeFrameName(IBslStackFrame frame) {
        try {
            return frame.getName();
        } catch (DebugException e) {
            return "";
        }
    }

    private static String safeThreadName(IRuntimeDebugTargetThread t) {
        try {
            return t.getName();
        } catch (DebugException e) {
            return "";
        }
    }
}
