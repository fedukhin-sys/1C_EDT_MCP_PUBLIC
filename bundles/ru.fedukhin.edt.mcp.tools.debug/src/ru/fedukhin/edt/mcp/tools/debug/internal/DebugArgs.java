package ru.fedukhin.edt.mcp.tools.debug.internal;

import java.util.Map;
import java.util.UUID;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/** Parses MCP tool arguments into typed values with clear {@link ToolException}s. */
public final class DebugArgs {

    private DebugArgs() {}

    /** Required string arg. */
    public static String stringArg(Map<String, Object> args, String key) throws ToolException {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            throw new ToolException("missing required argument: '" + key + "'");
        }
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new ToolException("argument '" + key + "' must be a non-empty string");
        }
        return (String) v;
    }

    /** Optional string arg — {@code null} if absent or not a string. */
    public static String optStringArg(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v instanceof String ? (String) v : null;
    }

    /** Required UUID arg (parsed from a string). */
    public static UUID uuidArg(Map<String, Object> args, String key) throws ToolException {
        String s = stringArg(args, key);
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid " + key + ": '" + s + "'");
        }
    }

    /** Required int arg (truthy in the JSON sense). */
    public static int intArg(Map<String, Object> args, String key) throws ToolException {
        Object v = args == null ? null : args.get(key);
        if (!(v instanceof Number)) {
            throw new ToolException("missing required integer argument: '" + key + "'");
        }
        return ((Number) v).intValue();
    }

    /** Optional int arg with a default; the resolved value must be within [min, max]. */
    public static int intArg(Map<String, Object> args, String key, int dflt, int min, int max)
            throws ToolException {
        Object v = args == null ? null : args.get(key);
        int value = v instanceof Number ? ((Number) v).intValue() : dflt;
        if (value < min || value > max) {
            throw new ToolException(key + " must be in [" + min + ", " + max + "], was " + value);
        }
        return value;
    }
}
