package ru.fedukhin.edt.mcp.tools.quality.internal;

import ru.fedukhin.edt.mcp.core.api.ToolException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parses MCP tool arguments for tools.quality. Mirrors {@code DebugArgs}. */
public final class QualityArgs {

    private QualityArgs() {}

    public static String stringArg(Map<String, Object> args, String key) throws ToolException {
        Object v = args == null ? null : args.get(key);
        if (v == null) throw new ToolException("missing required argument: '" + key + "'");
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new ToolException("argument '" + key + "' must be a non-empty string");
        }
        return (String) v;
    }

    public static String optStringArg(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
    }

    public static int intArg(Map<String, Object> args, String key, int dflt, int min, int max)
            throws ToolException {
        Object v = args == null ? null : args.get(key);
        int value = v instanceof Number ? ((Number) v).intValue() : dflt;
        if (value < min || value > max) {
            throw new ToolException(key + " must be in [" + min + ", " + max + "], was " + value);
        }
        return value;
    }

    public static boolean boolArg(Map<String, Object> args, String key, boolean dflt) {
        Object v = args == null ? null : args.get(key);
        return v instanceof Boolean ? (Boolean) v : dflt;
    }

    public static List<String> stringListArg(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (!(v instanceof List<?>)) return null;
        List<?> raw = (List<?>) v;
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof String s && !s.isEmpty()) out.add(s);
        }
        return out;
    }
}
