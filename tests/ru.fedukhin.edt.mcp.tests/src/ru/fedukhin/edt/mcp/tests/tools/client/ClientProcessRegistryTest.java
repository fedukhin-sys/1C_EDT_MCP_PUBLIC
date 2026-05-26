package ru.fedukhin.edt.mcp.tests.tools.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry.ClientSession;

public class ClientProcessRegistryTest {

    @Test
    public void register_returnsSessionWithPidAndAlive() {
        Process p = mock(Process.class);
        when(p.pid()).thenReturn(12345L);
        when(p.isAlive()).thenReturn(true);

        ClientProcessRegistry reg = new ClientProcessRegistry();
        ClientSession s = reg.register("Demo", "thin", p);

        assertNotNull(s.sessionId());
        assertEquals(12345L, s.pid());
        assertEquals("Demo", s.infobaseName());
        assertEquals("thin", s.clientType());
        assertTrue(s.alive());
    }

    @Test
    public void list_includesAlive_excludesDead() {
        Process pAlive = mock(Process.class);
        when(pAlive.pid()).thenReturn(1L); when(pAlive.isAlive()).thenReturn(true);
        Process pDead = mock(Process.class);
        when(pDead.pid()).thenReturn(2L); when(pDead.isAlive()).thenReturn(false);

        ClientProcessRegistry reg = new ClientProcessRegistry();
        reg.register("A", "thin", pAlive);
        reg.register("B", "thick", pDead);

        List<ClientSession> all = reg.list();
        assertEquals(1, all.size());
        assertEquals(1L, all.get(0).pid());
    }

    @Test
    public void get_returnsRegisteredSession() {
        Process p = mock(Process.class);
        when(p.pid()).thenReturn(3L); when(p.isAlive()).thenReturn(true);

        ClientProcessRegistry reg = new ClientProcessRegistry();
        ClientSession s = reg.register("X", "thin", p);
        assertEquals(s, reg.get(s.sessionId()).orElseThrow());
    }

    @Test
    public void remove_removesAndReturns() {
        Process p = mock(Process.class);
        when(p.pid()).thenReturn(4L); when(p.isAlive()).thenReturn(true);

        ClientProcessRegistry reg = new ClientProcessRegistry();
        ClientSession s = reg.register("Y", "thin", p);
        UUID id = s.sessionId();

        assertEquals(s, reg.remove(id).orElseThrow());
        assertFalse(reg.get(id).isPresent());
    }

    @Test
    public void register_concurrent_doesNotLoseSessions() throws Exception {
        ClientProcessRegistry reg = new ClientProcessRegistry();
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        Process p = mock(Process.class);
                        when(p.pid()).thenReturn((long)(idx * 1000 + i));
                        when(p.isAlive()).thenReturn(true);
                        reg.register("ib-" + idx, "thin", p);
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
