package ru.fedukhin.edt.mcp.core.internal.state;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import ru.fedukhin.edt.mcp.core.state.IServerStateBus;
import ru.fedukhin.edt.mcp.core.state.IServerStateListener;
import ru.fedukhin.edt.mcp.core.state.ServerState;

public class ServerStateBus implements IServerStateBus {

    private static final ILog LOG = Platform.getLog(ServerStateBus.class);

    private final AtomicReference<ServerState> state = new AtomicReference<>(ServerState.stopped());
    private final CopyOnWriteArrayList<IServerStateListener> listeners = new CopyOnWriteArrayList<>();

    public void publish(ServerState s) {
        state.set(s);
        for (IServerStateListener l : listeners) {
            try { l.onStateChanged(s); }
            catch (RuntimeException e) {
                LOG.log(Status.error("Server state listener threw", e));
            }
        }
    }

    @Override public ServerState current() { return state.get(); }
    @Override public void subscribe(IServerStateListener l) { listeners.addIfAbsent(l); }
    @Override public void unsubscribe(IServerStateListener l) { listeners.remove(l); }
}
