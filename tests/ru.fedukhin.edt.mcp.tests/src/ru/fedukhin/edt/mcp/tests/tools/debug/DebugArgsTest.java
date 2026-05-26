package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugArgs;

public class DebugArgsTest {

    @Test
    public void stringArg_present_returnsValue() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("k", "v");
        assertEquals("v", DebugArgs.stringArg(args, "k"));
    }

    @Test
    public void stringArg_missing_throwsToolException() {
        try {
            DebugArgs.stringArg(new LinkedHashMap<>(), "k");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertEquals(true, e.getMessage().contains("k"));
        }
    }

    @Test
    public void uuidArg_valid_parses() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", id.toString());
        assertEquals(id, DebugArgs.uuidArg(args, "debugSessionId"));
    }

    @Test
    public void uuidArg_invalid_throwsToolException() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debugSessionId", "not-a-uuid");
        try {
            DebugArgs.uuidArg(args, "debugSessionId");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertEquals(true, e.getMessage().toLowerCase().contains("invalid"));
        }
    }

    @Test
    public void intArg_missing_returnsDefault() throws Exception {
        assertEquals(30, DebugArgs.intArg(new LinkedHashMap<>(), "timeoutSeconds", 30, 1, 300));
    }

    @Test
    public void intArg_present_withinRange_returnsValue() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("timeoutSeconds", 5);
        assertEquals(5, DebugArgs.intArg(args, "timeoutSeconds", 30, 1, 300));
    }

    @Test
    public void intArg_outOfRange_throwsToolException() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("timeoutSeconds", 9999);
        try {
            DebugArgs.intArg(args, "timeoutSeconds", 30, 1, 300);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertEquals(true, e.getMessage().contains("timeoutSeconds"));
        }
    }

    @Test
    public void optStringArg_missing_returnsNull() {
        assertNull(DebugArgs.optStringArg(new LinkedHashMap<>(), "condition"));
    }

    @Test
    public void intArg_atMinBoundary_returnsValue() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("timeoutSeconds", 1);
        assertEquals(1, DebugArgs.intArg(args, "timeoutSeconds", 30, 1, 300));
    }

    @Test
    public void intArg_atMaxBoundary_returnsValue() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("timeoutSeconds", 300);
        assertEquals(300, DebugArgs.intArg(args, "timeoutSeconds", 30, 1, 300));
    }

    @Test
    public void stringArg_emptyString_throwsToolException() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("k", "");
        try {
            DebugArgs.stringArg(args, "k");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertEquals(true, e.getMessage().contains("k"));
        }
    }
}
