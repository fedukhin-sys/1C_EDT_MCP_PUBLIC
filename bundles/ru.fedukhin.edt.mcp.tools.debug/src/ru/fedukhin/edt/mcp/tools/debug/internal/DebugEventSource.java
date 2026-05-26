package ru.fedukhin.edt.mcp.tools.debug.internal;

import org.eclipse.debug.core.IDebugEventSetListener;

/**
 * Abstracts where debug events come from, so {@link DebugSession} can be unit-tested
 * against a fake source. The production impl ({@link DebugPluginEventSource}) uses the
 * global {@code DebugPlugin} bus; if the Phase 0 spike (risk #2) shows EDT does not
 * dispatch standard {@code DebugEvent}s there, Plan 2 swaps in an
 * {@code IRuntimeEventDispatcher}-backed impl — {@link DebugSession} stays unchanged.
 */
public interface DebugEventSource {
    void addListener(IDebugEventSetListener listener);
    void removeListener(IDebugEventSetListener listener);
}
