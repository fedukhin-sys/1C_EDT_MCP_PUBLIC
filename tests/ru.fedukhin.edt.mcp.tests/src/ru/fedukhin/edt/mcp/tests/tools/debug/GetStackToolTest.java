package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.GetStackTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.StackFrameDto;

public class GetStackToolTest {

    private final DebugSessionRegistry registry = mock(DebugSessionRegistry.class);
    private final GetStackTool tool = new GetStackTool(registry);

    @Test
    public void metadata_isCorrect() {
        assertEquals("get_stack", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }

    @Test
    public void call_happy_returnsFrameMaps() throws Exception {
        UUID id = UUID.randomUUID();
        DebugSession session = mock(DebugSession.class);
        when(session.getStack("0")).thenReturn(List.of(
                new StackFrameDto("0", "DemoIB", "src/M.bsl", 10, "Метод")));
        when(registry.require(id)).thenReturn(session);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("threadId", "0");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) tool.call(args);

        assertEquals(1, out.size());
        assertEquals("0", out.get(0).get("frameId"));
        assertEquals(10, out.get(0).get("line"));
    }

    @Test
    public void call_unknownSession_throwsToolException() throws Exception {
        UUID id = UUID.randomUUID();
        when(registry.require(id)).thenThrow(new ToolException("debug session '" + id + "' not found"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        args.put("threadId", "0");
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertEquals(true, e.getMessage().contains("not found"));
        }
    }
}
