package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.debug.ListDebugSessionsTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateDto;

public class ListDebugSessionsToolTest {

    private final DebugSessionRegistry registry = mock(DebugSessionRegistry.class);
    private final ListDebugSessionsTool tool = new ListDebugSessionsTool(registry);

    @Test
    public void metadata_isCorrect() {
        assertEquals("list_debug_sessions", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_empty_returnsEmptyList() throws Exception {
        when(registry.list()).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        List<Object> out = (List<Object>) tool.call(new LinkedHashMap<>());
        assertTrue(out.isEmpty());
    }

    @Test
    public void call_returnsSessionSummaries() throws Exception {
        UUID id = UUID.randomUUID();
        DebugSession s = mock(DebugSession.class);
        when(s.id()).thenReturn(id);
        when(s.getState()).thenReturn(
                new DebugStateDto(id.toString(), DebugStateDto.SUSPENDED, false, null, null));
        when(s.infobase()).thenReturn("DemoIB");
        when(s.clientType()).thenReturn("thin");
        when(s.startedAt()).thenReturn(Instant.parse("2026-05-14T10:00:00Z"));
        when(registry.list()).thenReturn(List.of(s));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) tool.call(new LinkedHashMap<>());

        assertEquals(1, out.size());
        assertEquals(id.toString(), out.get(0).get("debugSessionId"));
        assertEquals("suspended", out.get(0).get("state"));
        assertEquals("DemoIB", out.get(0).get("infobase"));
        assertEquals("thin", out.get(0).get("clientType"));
        assertEquals("2026-05-14T10:00:00Z", out.get(0).get("startedAt"));
    }
}
