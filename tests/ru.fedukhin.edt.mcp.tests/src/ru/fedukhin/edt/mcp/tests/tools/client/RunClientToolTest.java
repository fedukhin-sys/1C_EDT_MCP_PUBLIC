package ru.fedukhin.edt.mcp.tests.tools.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.RunClientTool;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientLauncher;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry;
import ru.fedukhin.edt.mcp.tools.client.internal.InfobaseLookup;

public class RunClientToolTest {

    @Test
    public void call_happy_returnsSessionFields() throws Exception {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        InfobaseLookup lookup = mock(InfobaseLookup.class);
        when(lookup.findByName("Demo")).thenReturn(Optional.of(ref));

        Process p = mock(Process.class);
        when(p.pid()).thenReturn(99L);
        when(p.isAlive()).thenReturn(true);
        ClientLauncher launcher = mock(ClientLauncher.class);
        when(launcher.launch(eq(ref), eq("thin"), eq("u"), eq("pw"))).thenReturn(p);

        ClientProcessRegistry registry = new ClientProcessRegistry();

        Map<String, Object> args = new HashMap<>();
        args.put("infobase", "Demo");
        args.put("user", "u");
        args.put("password", "pw");
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) new RunClientTool(lookup, launcher, registry).call(args);

        assertNotNull(out.get("sessionId"));
        UUID.fromString((String) out.get("sessionId"));   // valid UUID
        assertEquals(99L, ((Number) out.get("pid")).longValue());
        assertEquals("thin", out.get("clientType"));
        assertEquals("Demo", out.get("infobase"));
        assertNotNull(out.get("startedAt"));
        Instant.parse((String) out.get("startedAt"));     // valid ISO
    }

    @Test
    public void call_clientTypeOmitted_defaultsThinAndForwarded() throws Exception {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        InfobaseLookup lookup = mock(InfobaseLookup.class);
        when(lookup.findByName("Demo")).thenReturn(Optional.of(ref));

        Process p = mock(Process.class);
        when(p.pid()).thenReturn(1L);
        when(p.isAlive()).thenReturn(true);
        ClientLauncher launcher = mock(ClientLauncher.class);
        when(launcher.launch(eq(ref), eq("thin"), eq(null), eq(null))).thenReturn(p);

        ClientProcessRegistry registry = new ClientProcessRegistry();

        Map<String, Object> args = new HashMap<>();
        args.put("infobase", "Demo");
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) new RunClientTool(lookup, launcher, registry).call(args);

        assertEquals("thin", out.get("clientType"));
        verify(launcher).launch(eq(ref), eq("thin"), eq(null), eq(null));
    }

    @Test
    public void call_clientTypeThick_passedThrough() throws Exception {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        InfobaseLookup lookup = mock(InfobaseLookup.class);
        when(lookup.findByName("Demo")).thenReturn(Optional.of(ref));

        Process p = mock(Process.class);
        when(p.pid()).thenReturn(2L);
        when(p.isAlive()).thenReturn(true);
        ClientLauncher launcher = mock(ClientLauncher.class);
        when(launcher.launch(eq(ref), eq("thick"), eq(null), eq(null))).thenReturn(p);

        ClientProcessRegistry registry = new ClientProcessRegistry();

        Map<String, Object> args = new HashMap<>();
        args.put("infobase", "Demo");
        args.put("clientType", "thick");
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) new RunClientTool(lookup, launcher, registry).call(args);

        assertEquals("thick", out.get("clientType"));
    }

    @Test
    public void call_infobaseNotFound_throws() {
        InfobaseLookup lookup = mock(InfobaseLookup.class);
        when(lookup.findByName("Missing")).thenReturn(Optional.empty());

        Map<String, Object> args = new HashMap<>();
        args.put("infobase", "Missing");
        try {
            new RunClientTool(lookup, mock(ClientLauncher.class), new ClientProcessRegistry()).call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }
}
