package ru.fedukhin.edt.mcp.tests.tools.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.StopClientTool;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry.ClientSession;

public class StopClientToolTest {

    @Test
    public void call_happy_gracefulSucceeds() throws Exception {
        Process p = mock(Process.class);
        when(p.pid()).thenReturn(1L);
        // alive when stop_client checks; graceful destroy succeeds
        when(p.isAlive()).thenReturn(true);
        when(p.waitFor(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(p.exitValue()).thenReturn(0);

        ClientProcessRegistry registry = new ClientProcessRegistry();
        ClientSession s = registry.register("Demo", "thin", p);

        Map<String, Object> args = new HashMap<>();
        args.put("sessionId", s.sessionId().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) new StopClientTool(registry).call(args);

        assertTrue((Boolean) out.get("stopped"));
        assertEquals(0, ((Number) out.get("exitCode")).intValue());
        verify(p).destroy();
        verify(p, never()).destroyForcibly();
        assertFalse(registry.get(s.sessionId()).isPresent());
    }

    @Test
    public void call_force_skipsGracefulAndKills() throws Exception {
        Process p = mock(Process.class);
        when(p.pid()).thenReturn(2L);
        when(p.isAlive()).thenReturn(true);
        when(p.waitFor(2L, TimeUnit.SECONDS)).thenReturn(true);
        when(p.exitValue()).thenReturn(137);
        when(p.destroyForcibly()).thenReturn(p);

        ClientProcessRegistry registry = new ClientProcessRegistry();
        ClientSession s = registry.register("Demo", "thin", p);

        Map<String, Object> args = new HashMap<>();
        args.put("sessionId", s.sessionId().toString());
        args.put("force", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) new StopClientTool(registry).call(args);

        assertTrue((Boolean) out.get("stopped"));
        assertEquals(137, ((Number) out.get("exitCode")).intValue());
        verify(p).destroyForcibly();
        verify(p, never()).destroy();
    }

    @Test
    public void call_alreadyDead_returnsExitCode() throws Exception {
        Process p = mock(Process.class);
        when(p.pid()).thenReturn(3L);
        // already dead by the time stop_client runs
        when(p.isAlive()).thenReturn(false);
        when(p.exitValue()).thenReturn(0);

        ClientProcessRegistry registry = new ClientProcessRegistry();
        ClientSession s = registry.register("Demo", "thin", p);

        Map<String, Object> args = new HashMap<>();
        args.put("sessionId", s.sessionId().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) new StopClientTool(registry).call(args);

        assertTrue((Boolean) out.get("stopped"));
        assertEquals(0, ((Number) out.get("exitCode")).intValue());
        verify(p, never()).destroy();
        verify(p, never()).destroyForcibly();
    }

    @Test
    public void call_unknownSessionId_throws() {
        ClientProcessRegistry registry = new ClientProcessRegistry();
        Map<String, Object> args = new HashMap<>();
        args.put("sessionId", UUID.randomUUID().toString());
        try {
            new StopClientTool(registry).call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_invalidUuid_throws() {
        ClientProcessRegistry registry = new ClientProcessRegistry();
        Map<String, Object> args = new HashMap<>();
        args.put("sessionId", "not-a-uuid");
        try {
            new StopClientTool(registry).call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }
}
