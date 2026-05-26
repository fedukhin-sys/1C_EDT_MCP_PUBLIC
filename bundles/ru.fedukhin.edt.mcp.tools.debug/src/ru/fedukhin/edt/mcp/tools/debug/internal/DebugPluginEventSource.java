package ru.fedukhin.edt.mcp.tools.debug.internal;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;

/** Production {@link DebugEventSource}: the global Eclipse {@code DebugPlugin} event bus. */
public class DebugPluginEventSource implements DebugEventSource {

    @Override
    public void addListener(IDebugEventSetListener listener) {
        DebugPlugin.getDefault().addDebugEventListener(listener);
    }

    @Override
    public void removeListener(IDebugEventSetListener listener) {
        DebugPlugin.getDefault().removeDebugEventListener(listener);
    }
}
