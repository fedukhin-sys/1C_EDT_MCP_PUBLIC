package ru.fedukhin.edt.mcp.tools.testrun.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes a test selector to a {@code /C} startup parameter format consumed
 * by the BSL client runner.
 *
 * <p>Format: {@code EDT_MCP_TESTS=mode=<MTD|MOD|ALL>;rf=<base64-path>[;mod=…][;mtd=…]}
 * The {@code resultFile} path is Base64-encoded to avoid breaking the
 * semicolon/equals delimiters when paths contain spaces, Cyrillic, or punctuation.
 */
public final class TestSelectorEncoder {

    private static final String PREFIX = "EDT_MCP_TESTS=";

    private TestSelectorEncoder() {}

    public static String encodeAll(String resultFilePath) {
        return PREFIX + "mode=ALL;rf=" + base64(resultFilePath);
    }

    public static String encodeModule(String moduleFqn, String resultFilePath) {
        return PREFIX + "mode=MOD;rf=" + base64(resultFilePath) + ";mod=" + moduleFqn;
    }

    public static String encodeMethod(String moduleFqn, String methodName, String resultFilePath) {
        return PREFIX + "mode=MTD;rf=" + base64(resultFilePath)
            + ";mod=" + moduleFqn + ";mtd=" + methodName;
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
