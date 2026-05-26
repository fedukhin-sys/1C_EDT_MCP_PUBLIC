package ru.fedukhin.edt.mcp.tests.tools.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.client.ListRunningClientsTool;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry;

public class ListRunningClientsToolTest {

    @Test
    public void call_emptyRegistry_returnsEmptyList() throws Exception {
        ClientProcessRegistry registry = new ClientProcessRegistry();
        Object result = new ListRunningClientsTool(registry).call(new HashMap<>());
        assertTrue(result instanceof List);
        assertTrue(((List<?>) result).isEmpty());
    }

    @Test
    public void call_singleSession_serialisesAllFields() throws Exception {
        Process p = mock(Process.class);
        when(p.pid()).thenReturn(42L); when(p.isAlive()).thenReturn(true);

        ClientProcessRegistry registry = new ClientProcessRegistry();
        registry.register("Demo", "thin", p);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>)
            new ListRunningClientsTool(registry).call(new HashMap<>());
        assertEquals(1, items.size());
        Map<String, Object> entry = items.get(0);
        assertEquals(42L, ((Number) entry.get("pid")).longValue());
        assertEquals("thin", entry.get("clientType"));
        assertEquals("Demo", entry.get("infobase"));
        assertEquals(true, entry.get("alive"));
    }

    @Test
    public void call_filterByInfobase_excludes() throws Exception {
        Process p1 = mock(Process.class);
        when(p1.pid()).thenReturn(1L); when(p1.isAlive()).thenReturn(true);
        Process p2 = mock(Process.class);
        when(p2.pid()).thenReturn(2L); when(p2.isAlive()).thenReturn(true);

        ClientProcessRegistry registry = new ClientProcessRegistry();
        registry.register("A", "thin", p1);
        registry.register("B", "thin", p2);

        Map<String, Object> args = new HashMap<>();
        args.put("infobase", "B");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>)
            new ListRunningClientsTool(registry).call(args);
        assertEquals(1, items.size());
        assertEquals("B", items.get(0).get("infobase"));
    }

    @Test
    public void call_filterByClientType_excludes() throws Exception {
        Process pThin = mock(Process.class);
        when(pThin.pid()).thenReturn(1L); when(pThin.isAlive()).thenReturn(true);
        Process pThick = mock(Process.class);
        when(pThick.pid()).thenReturn(2L); when(pThick.isAlive()).thenReturn(true);

        ClientProcessRegistry registry = new ClientProcessRegistry();
        registry.register("Demo", "thin", pThin);
        registry.register("Demo", "thick", pThick);

        Map<String, Object> args = new HashMap<>();
        args.put("clientType", "thick");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>)
            new ListRunningClientsTool(registry).call(args);
        assertEquals(1, items.size());
        assertEquals("thick", items.get(0).get("clientType"));
    }
}
