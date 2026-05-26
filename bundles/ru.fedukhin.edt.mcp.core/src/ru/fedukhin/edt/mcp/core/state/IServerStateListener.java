package ru.fedukhin.edt.mcp.core.state;

public interface IServerStateListener {
    void onStateChanged(ServerState newState);
}
