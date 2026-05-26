package ru.fedukhin.edt.mcp.core.internal.preferences;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;

public class McpPreferences {

    public static final String KEY_PORT = "port";
    public static final String KEY_AUTO_START = "autoStart";
    public static final String KEY_BIND_HOST = "bindHost";

    public static final int DEFAULT_PORT = 3001;
    public static final boolean DEFAULT_AUTO_START = false;
    public static final String DEFAULT_BIND_HOST = "127.0.0.1";

    private final IEclipsePreferences node;

    public McpPreferences(IEclipsePreferences node) {
        this.node = node;
    }

    public int getPort() { return node.getInt(KEY_PORT, DEFAULT_PORT); }
    public boolean isAutoStart() { return node.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START); }
    public String getBindHost() { return node.get(KEY_BIND_HOST, DEFAULT_BIND_HOST); }

    public void setPort(int v) { node.putInt(KEY_PORT, v); }
    public void setAutoStart(boolean v) { node.putBoolean(KEY_AUTO_START, v); }
}
