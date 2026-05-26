package ru.fedukhin.edt.mcp.tests.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.internal.state.ServerStateBus;
import ru.fedukhin.edt.mcp.core.state.IServerStateListener;
import ru.fedukhin.edt.mcp.core.state.ServerState;

public class ServerStateBusTest {

    @Test
    public void initial_isStopped() {
        ServerStateBus bus = new ServerStateBus();
        assertEquals(ServerState.stopped(), bus.current());
    }

    @Test
    public void publish_notifiesSubscribers() {
        ServerStateBus bus = new ServerStateBus();
        List<ServerState> seen = new ArrayList<>();
        IServerStateListener l = seen::add;
        bus.subscribe(l);
        bus.publish(ServerState.starting(3001));
        bus.publish(ServerState.running(3001));
        assertEquals(2, seen.size());
        assertEquals(ServerState.starting(3001), seen.get(0));
        assertEquals(ServerState.running(3001), seen.get(1));
        assertSame(ServerState.running(3001).phase(), bus.current().phase());
    }

    @Test
    public void unsubscribe_stopsNotifications() {
        ServerStateBus bus = new ServerStateBus();
        List<ServerState> seen = new ArrayList<>();
        IServerStateListener l = seen::add;
        bus.subscribe(l);
        bus.unsubscribe(l);
        bus.publish(ServerState.running(3001));
        assertEquals(0, seen.size());
    }

    @Test
    public void listenerException_isIsolated() {
        ServerStateBus bus = new ServerStateBus();
        bus.subscribe(s -> { throw new RuntimeException("boom"); });
        List<ServerState> seen = new ArrayList<>();
        bus.subscribe(seen::add);
        bus.publish(ServerState.running(3001));
        assertEquals(1, seen.size());
    }
}
