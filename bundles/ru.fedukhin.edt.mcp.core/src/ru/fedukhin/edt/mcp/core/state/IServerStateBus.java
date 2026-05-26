package ru.fedukhin.edt.mcp.core.state;

public interface IServerStateBus {
    ServerState current();
    void subscribe(IServerStateListener listener);
    void unsubscribe(IServerStateListener listener);
}
