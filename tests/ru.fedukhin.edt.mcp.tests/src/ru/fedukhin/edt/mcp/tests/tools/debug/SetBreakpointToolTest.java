package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.SetBreakpointTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointInfo;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointService;

public class SetBreakpointToolTest {

    private final BreakpointService service = mock(BreakpointService.class);
    private final SetBreakpointTool tool = new SetBreakpointTool(service);

    @Test
    public void metadata_isCorrect() {
        assertEquals("set_breakpoint", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_happy_returnsBreakpointInfoMap() throws Exception {
        when(service.setBreakpoint("DemoIB", "src/M.bsl", 42, null))
                .thenReturn(new BreakpointInfo("101", "DemoIB", "src/M.bsl", 42, null));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("project", "DemoIB");
        args.put("path", "src/M.bsl");
        args.put("line", 42);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args);

        assertEquals("101", out.get("breakpointId"));
        assertEquals("DemoIB", out.get("project"));
        assertEquals("src/M.bsl", out.get("path"));
        assertEquals(42, out.get("line"));
        assertTrue(out.containsKey("condition"));
        assertNull(out.get("condition")); // unconditional breakpoint → explicit null in the JSON response
    }

    @Test
    public void call_withCondition_forwardsCondition() throws Exception {
        when(service.setBreakpoint("DemoIB", "src/M.bsl", 10, "X > 0"))
                .thenReturn(new BreakpointInfo("102", "DemoIB", "src/M.bsl", 10, "X > 0"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("project", "DemoIB");
        args.put("path", "src/M.bsl");
        args.put("line", 10);
        args.put("condition", "X > 0");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args);

        assertEquals("X > 0", out.get("condition"));
    }

    @Test
    public void call_missingLine_throwsToolException() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("project", "DemoIB");
        args.put("path", "src/M.bsl");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("line"));
        }
    }
}
