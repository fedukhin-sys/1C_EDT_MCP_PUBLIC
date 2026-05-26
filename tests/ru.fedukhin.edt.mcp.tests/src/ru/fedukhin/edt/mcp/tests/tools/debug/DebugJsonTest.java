package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointInfo;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugJson;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateDto;
import ru.fedukhin.edt.mcp.tools.debug.internal.SourceLocation;
import ru.fedukhin.edt.mcp.tools.debug.internal.StackFrameDto;
import ru.fedukhin.edt.mcp.tools.debug.internal.ThreadRef;
import ru.fedukhin.edt.mcp.tools.debug.internal.VariableDto;

public class DebugJsonTest {

    @Test
    public void stateToMap_running_omitsThreadAndLocation() {
        DebugStateDto dto = new DebugStateDto("sid", DebugStateDto.RUNNING, true, null, null);
        Map<String, Object> m = DebugJson.stateToMap(dto);
        assertEquals("sid", m.get("debugSessionId"));
        assertEquals("running", m.get("state"));
        assertEquals(Boolean.TRUE, m.get("timedOut"));
        assertNull(m.get("stoppedThread"));
        assertNull(m.get("location"));
    }

    @Test
    public void stateToMap_suspended_includesThreadAndLocation() {
        DebugStateDto dto = new DebugStateDto("sid", DebugStateDto.SUSPENDED, false,
                new ThreadRef("0", "Основной"),
                new SourceLocation("DemoIB", "src/M.bsl", 42, "Метод"));
        Map<String, Object> m = DebugJson.stateToMap(dto);
        assertEquals("suspended", m.get("state"));
        @SuppressWarnings("unchecked")
        Map<String, Object> thread = (Map<String, Object>) m.get("stoppedThread");
        assertEquals("0", thread.get("id"));
        assertEquals("Основной", thread.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> loc = (Map<String, Object>) m.get("location");
        assertEquals("DemoIB", loc.get("project"));
        assertEquals(42, loc.get("line"));
        assertEquals("Метод", loc.get("method"));
    }

    @Test
    public void frameToMap_mapsAllFields() {
        StackFrameDto dto = new StackFrameDto("2", "DemoIB", "src/M.bsl", 7, "Метод");
        Map<String, Object> m = DebugJson.frameToMap(dto);
        assertEquals("2", m.get("frameId"));
        assertEquals("DemoIB", m.get("project"));
        assertEquals("src/M.bsl", m.get("path"));
        assertEquals(7, m.get("line"));
        assertEquals("Метод", m.get("method"));
    }

    @Test
    public void breakpointToMap_mapsAllFields() {
        BreakpointInfo info = new BreakpointInfo("101", "DemoIB", "src/M.bsl", 42, "X > 0");
        Map<String, Object> m = DebugJson.breakpointToMap(info);
        assertEquals("101", m.get("breakpointId"));
        assertEquals("DemoIB", m.get("project"));
        assertEquals("src/M.bsl", m.get("path"));
        assertEquals(42, m.get("line"));
        assertEquals("X > 0", m.get("condition"));
    }

    @Test
    public void variableToMap_mapsAllThreeFields() {
        VariableDto dto = new VariableDto("Счётчик", "Число", "42");
        Map<String, Object> m = DebugJson.variableToMap(dto);
        assertEquals("Счётчик", m.get("name"));
        assertEquals("Число", m.get("type"));
        assertEquals("42", m.get("value"));
    }
}
