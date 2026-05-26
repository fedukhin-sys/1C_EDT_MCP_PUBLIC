package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.debug.ListBreakpointsTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointInfo;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointService;

public class ListBreakpointsToolTest {

    private final BreakpointService service = mock(BreakpointService.class);
    private final ListBreakpointsTool tool = new ListBreakpointsTool(service);

    @Test
    public void metadata_isCorrect() {
        assertEquals("list_breakpoints", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_empty_returnsEmptyList() throws Exception {
        when(service.list()).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        List<Object> out = (List<Object>) tool.call(new LinkedHashMap<>());
        assertTrue(out.isEmpty());
    }

    @Test
    public void call_returnsBreakpointMaps() throws Exception {
        when(service.list()).thenReturn(List.of(
                new BreakpointInfo("101", "DemoIB", "src/A.bsl", 5, null),
                new BreakpointInfo("102", "DemoIB", "src/B.bsl", 9, "X > 0")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) tool.call(new LinkedHashMap<>());
        assertEquals(2, out.size());
        assertEquals("101", out.get(0).get("breakpointId"));
        assertEquals(5, out.get(0).get("line"));
        assertEquals("X > 0", out.get(1).get("condition"));
        assertTrue(out.get(0).containsKey("condition"));
        assertNull(out.get(0).get("condition")); // unconditional breakpoint → explicit null
    }
}
