package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.RemoveBreakpointTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointService;

public class RemoveBreakpointToolTest {

    private final BreakpointService service = mock(BreakpointService.class);
    private final RemoveBreakpointTool tool = new RemoveBreakpointTool(service);

    @Test
    public void metadata_isCorrect() {
        assertEquals("remove_breakpoint", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_happy_delegatesToServiceAndReturnsRemoved() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("breakpointId", "101");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.call(args);

        verify(service).remove("101");
        assertEquals(Boolean.TRUE, out.get("removed"));
    }

    @Test
    public void call_missingBreakpointId_throwsToolException() {
        try {
            tool.call(new LinkedHashMap<>());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("breakpointId"));
        }
    }
}
