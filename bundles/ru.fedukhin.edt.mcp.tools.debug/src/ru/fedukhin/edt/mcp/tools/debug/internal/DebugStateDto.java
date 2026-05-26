package ru.fedukhin.edt.mcp.tools.debug.internal;

/**
 * Snapshot of a debug session, returned by the synchronous control verbs and {@code get_debug_state}.
 * {@code state} is one of {@code "running"}, {@code "suspended"}, {@code "terminated"}.
 * {@code stoppedThread} / {@code location} are non-null only when {@code state == "suspended"}.
 * {@code timedOut} is meaningful only for {@code debug_resume} / {@code debug_step}.
 */
public record DebugStateDto(String debugSessionId, String state, boolean timedOut,
                            ThreadRef stoppedThread, SourceLocation location) {

    public static final String RUNNING = "running";
    public static final String SUSPENDED = "suspended";
    public static final String TERMINATED = "terminated";
}
