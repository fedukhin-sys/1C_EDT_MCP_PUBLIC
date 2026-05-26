package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestSelectorEncoder;

public class TestSelectorEncoderTest {

    @Test public void encodeSingleMethod_keyValueFormatWithBase64Path() {
        String encoded = TestSelectorEncoder.encodeMethod(
            "CommonModule.МойТест", "Тест_X", "C:\\Users\\User\\AppData\\result.json");
        assertTrue("must start with EDT_MCP_TESTS=", encoded.startsWith("EDT_MCP_TESTS="));
        assertTrue("must contain mode=MTD", encoded.contains("mode=MTD"));
        assertTrue("must contain mod= (key)", encoded.contains("mod=CommonModule.МойТест"));
        assertTrue("must contain mtd=", encoded.contains("mtd=Тест_X"));
        assertTrue("must contain rf= (base64-encoded resultFile)", encoded.contains("rf="));
        // resultFile must NOT appear in clear text — it's encoded
        assertTrue("path must NOT appear unencoded",
            !encoded.contains("C:\\\\Users\\\\User"));
    }

    @Test public void encodeModule_modOnly_noMtd() {
        String encoded = TestSelectorEncoder.encodeModule(
            "CommonModule.МойТест", "/tmp/r.json");
        assertTrue(encoded.contains("mode=MOD"));
        assertTrue(encoded.contains("mod=CommonModule.МойТест"));
        assertTrue("must NOT contain mtd=", !encoded.contains("mtd="));
    }

    @Test public void encodeAll_neitherModuleNorMethod() {
        String encoded = TestSelectorEncoder.encodeAll("/tmp/r.json");
        assertTrue(encoded.contains("mode=ALL"));
        assertTrue(!encoded.contains("mod="));
        assertTrue(!encoded.contains("mtd="));
    }

    @Test public void encodedString_keyValueDelimiters_consistent() {
        // selector format is k=v;k=v;… — keys/values must not break the format
        String encoded = TestSelectorEncoder.encodeMethod(
            "CommonModule.С_Подчерком", "Тест_Y", "/tmp/x.json");
        // After the EDT_MCP_TESTS= prefix, we have k=v pairs separated by ';'.
        // For method mode, we expect 4 pairs: mode, rf, mod, mtd
        String tail = encoded.substring("EDT_MCP_TESTS=".length());
        int semCount = countChar(tail, ';');
        assertEquals("method encoding should have exactly 3 semicolons (4 pairs)", 3, semCount);
    }

    @Test public void encodeAll_base64IsValid() {
        // The Base64 portion should round-trip back to the original path.
        String original = "C:\\Users\\User\\AppData\\Local\\Temp\\edt-mcp-test-results\\abc.json";
        String encoded = TestSelectorEncoder.encodeAll(original);
        int idx = encoded.indexOf("rf=");
        String b64 = encoded.substring(idx + 3);
        // Decode and verify roundtrip
        String decoded = new String(java.util.Base64.getDecoder().decode(b64),
            java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(original, decoded);
    }

    private static int countChar(String s, char c) {
        int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n;
    }
}
