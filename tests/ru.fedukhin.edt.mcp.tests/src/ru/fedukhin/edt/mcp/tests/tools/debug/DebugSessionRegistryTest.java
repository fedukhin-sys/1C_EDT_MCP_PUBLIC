package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;

public class DebugSessionRegistryTest {

    private static DebugSession sessionMock(UUID id, boolean terminated) {
        DebugSession s = mock(DebugSession.class);
        when(s.id()).thenReturn(id);
        when(s.isTerminated()).thenReturn(terminated);
        when(s.infobase()).thenReturn("DemoIB");
        when(s.clientType()).thenReturn("thin");
        when(s.startedAt()).thenReturn(Instant.now());
        return s;
    }

    @Test
    public void register_thenGet_returnsSameSession() {
        DebugSessionRegistry reg = new DebugSessionRegistry();
        UUID id = UUID.randomUUID();
        DebugSession s = sessionMock(id, false);

        reg.register(s);

        assertEquals(s, reg.get(id).orElseThrow());
    }

    @Test
    public void list_excludesTerminatedSessions() {
        DebugSessionRegistry reg = new DebugSessionRegistry();
        DebugSession alive = sessionMock(UUID.randomUUID(), false);
        DebugSession dead = sessionMock(UUID.randomUUID(), true);
        reg.register(alive);
        reg.register(dead);

        List<DebugSession> all = reg.list();

        assertEquals(1, all.size());
        assertEquals(alive, all.get(0));
    }

    @Test
    public void remove_removesAndReturns() {
        DebugSessionRegistry reg = new DebugSessionRegistry();
        UUID id = UUID.randomUUID();
        DebugSession s = sessionMock(id, false);
        reg.register(s);

        assertEquals(s, reg.remove(id).orElseThrow());
        assertFalse(reg.get(id).isPresent());
    }

    @Test
    public void get_unknownId_returnsEmpty() {
        DebugSessionRegistry reg = new DebugSessionRegistry();
        assertFalse(reg.get(UUID.randomUUID()).isPresent());
    }

    @Test
    public void list_disposesTerminatedSessionsWhilePruning() {
        DebugSessionRegistry reg = new DebugSessionRegistry();
        DebugSession alive = sessionMock(UUID.randomUUID(), false);
        DebugSession dead = sessionMock(UUID.randomUUID(), true);
        reg.register(alive);
        reg.register(dead);

        List<DebugSession> all = reg.list();

        assertEquals(1, all.size());
        assertEquals(alive, all.get(0));
        verify(dead).dispose();              // pruned terminated session must be disposed
        verify(alive, never()).dispose();    // live session must NOT be disposed
    }

    @Test
    public void require_present_returnsSession() throws ToolException {
        DebugSessionRegistry reg = new DebugSessionRegistry();
        UUID id = UUID.randomUUID();
        DebugSession s = sessionMock(id, false);
        reg.register(s);
        assertEquals(s, reg.require(id));
    }

    @Test
    public void require_absent_throwsToolException() {
        DebugSessionRegistry reg = new DebugSessionRegistry();
        try {
            reg.require(UUID.randomUUID());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    @Test
    public void remove_disposesRemovedSession() {
        DebugSessionRegistry reg = new DebugSessionRegistry();
        UUID id = UUID.randomUUID();
        DebugSession s = sessionMock(id, false);
        reg.register(s);

        java.util.Optional<DebugSession> removed = reg.remove(id);

        assertTrue(removed.isPresent());
        assertEquals(s, removed.get());
        verify(s).dispose();             // session must be disposed when explicitly removed
    }

    @Test
    public void register_concurrent_doesNotLoseSessions() throws Exception {
        DebugSessionRegistry reg = new DebugSessionRegistry();
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        reg.register(sessionMock(UUID.randomUUID(), false));
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(threads * perThread, reg.list().size());
    }
}
