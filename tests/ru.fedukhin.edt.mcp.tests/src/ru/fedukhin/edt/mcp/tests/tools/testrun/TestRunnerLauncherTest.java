package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunResult;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher.ProcessRunner;

public class TestRunnerLauncherTest {

    private TestRunnerLauncher launcher;

    @After
    public void tearDown() {
        if (launcher != null) {
            launcher.shutdown();
        }
    }

    @Test
    public void runWithTimeout_writesResultFile_parsesAndReturns() throws Exception {
        // Fake ProcessRunner that simulates 1cv8: writes a valid JSON result, exits 0.
        ProcessRunner fake = (cmd, env, timeoutSec) -> {
            Path rf = extractResultFile(cmd);
            Files.writeString(rf, "{\"passed\":1,\"failed\":0,\"durationMs\":10,\"tests\":["
                + "{\"module\":\"M\",\"name\":\"Т\",\"status\":\"passed\",\"durationMs\":5}]}");
            return new ProcessRunner.RunOutcome(0, "", "");
        };
        launcher = new TestRunnerLauncher(fake);
        TestRunResult r = launcher.runWithTimeout(
            "C:\\1cv8\\bin\\1cv8.exe", "File=\"E:\\IB\";",
            "EDT_MCP_TESTS=mode=ALL;rf=" + base64(systemTempJson()), 30);
        assertEquals(1, r.passed());
        assertEquals(0, r.failed());
        assertEquals("passed", r.tests().get(0).status());
    }

    @Test
    public void runWithTimeout_processExitsButNoResultFile_throws() {
        ProcessRunner fake = (cmd, env, timeoutSec) -> new ProcessRunner.RunOutcome(0, "", "stderr-tail");
        launcher = new TestRunnerLauncher(fake);
        try {
            launcher.runWithTimeout("1cv8.exe", "File=\"E:\\IB\";",
                "EDT_MCP_TESTS=mode=ALL;rf=" + base64(systemTempJson()), 30);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("no result file"));
            assertTrue(e.getMessage().contains("stderr-tail"));
        }
    }

    /**
     * До этого раннер вообще не умел /N /P — тесты можно было гонять только по
     * OS-аутентификации или в ИБ без пользователей.
     */
    @Test
    public void runWithTimeout_userAndPassword_appendedAsSlashNSlashP() throws Exception {
        List<List<String>> seen = new java.util.ArrayList<>();
        ProcessRunner fake = (cmd, env, timeoutSec) -> {
            seen.add(cmd);
            Path rf = extractResultFile(cmd);
            Files.writeString(rf, "{\"passed\":0,\"failed\":0,\"durationMs\":1,\"tests\":[]}");
            return new ProcessRunner.RunOutcome(0, "", "");
        };
        launcher = new TestRunnerLauncher(fake);
        launcher.runWithTimeout("1cv8.exe", "File=\"E:\\IB\";",
            "EDT_MCP_TESTS=mode=ALL;rf=" + base64(systemTempJson()), 30, "Админ", "s3cret");

        List<String> cmd = seen.get(0);
        int n = cmd.indexOf("/N");
        assertTrue("ожидался /N в команде: " + cmd, n >= 0);
        assertEquals("Админ", cmd.get(n + 1));
        int p = cmd.indexOf("/P");
        assertTrue("ожидался /P в команде: " + cmd, p >= 0);
        assertEquals("s3cret", cmd.get(p + 1));
    }

    @Test
    public void runWithTimeout_noCredentials_noSlashNSlashP() throws Exception {
        List<List<String>> seen = new java.util.ArrayList<>();
        ProcessRunner fake = (cmd, env, timeoutSec) -> {
            seen.add(cmd);
            Path rf = extractResultFile(cmd);
            Files.writeString(rf, "{\"passed\":0,\"failed\":0,\"durationMs\":1,\"tests\":[]}");
            return new ProcessRunner.RunOutcome(0, "", "");
        };
        launcher = new TestRunnerLauncher(fake);
        launcher.runWithTimeout("1cv8.exe", "File=\"E:\\IB\";",
            "EDT_MCP_TESTS=mode=ALL;rf=" + base64(systemTempJson()), 30, null, null);

        assertTrue("без учётных данных /N быть не должно: " + seen.get(0),
            seen.get(0).indexOf("/N") < 0);
        assertTrue(seen.get(0).indexOf("/P") < 0);
    }

    /** Текст ошибки печатает всю командную строку — пароль в него попасть не должен. */
    @Test
    public void runWithTimeout_errorMessage_doesNotLeakPassword() {
        ProcessRunner fake = (cmd, env, timeoutSec) -> new ProcessRunner.RunOutcome(1, "", "boom");
        launcher = new TestRunnerLauncher(fake);
        try {
            launcher.runWithTimeout("1cv8.exe", "File=\"E:\\IB\";",
                "EDT_MCP_TESTS=mode=ALL;rf=" + base64(systemTempJson()), 30, "Админ", "s3cret");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue("пароль не должен попадать в сообщение: " + e.getMessage(),
                !e.getMessage().contains("s3cret"));
        }
    }

    @Test
    public void appendIbArgs_fileConnection_usesSlashFShortcut() {
        // Regression: Java ProcessBuilder on Windows escapes embedded " as \", which 1cv8.exe
        // does NOT recognise in /IBConnectionString File="..." — 1cv8 returns
        // "Неопределена информационная база" via /Out. Caught on Stage 7b live smoke 2026-05-17.
        java.util.List<String> cmd = new java.util.ArrayList<>();
        TestRunnerLauncher.appendIbArgs(cmd, "File=\"E:\\EDTProjects\\TEST\\DemoIB2\";");
        assertEquals(java.util.List.of("/FE:\\EDTProjects\\TEST\\DemoIB2"), cmd);
    }

    @Test
    public void appendIbArgs_serverConnection_usesSlashSShortcut() {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        TestRunnerLauncher.appendIbArgs(cmd, "Srvr=\"asus-tuf\";Ref=\"wms\";");
        assertEquals(java.util.List.of("/Sasus-tuf\\wms"), cmd);
    }

    @Test
    public void appendIbArgs_unknownConnection_fallsBackToIBConnectionString() {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        TestRunnerLauncher.appendIbArgs(cmd, "WS=\"http://example.com\";");
        assertEquals(java.util.List.of("/IBConnectionString", "WS=\"http://example.com\";"), cmd);
    }

    @Test
    public void runWithTimeout_timeoutSecondsOutOfRange_throws() {
        ProcessRunner fake = (cmd, env, timeoutSec) -> new ProcessRunner.RunOutcome(0, "", "");
        launcher = new TestRunnerLauncher(fake);
        for (int bad : new int[]{29, 3601}) {
            try {
                launcher.runWithTimeout("1cv8.exe", "F", "EDT_MCP_TESTS=", bad);
                fail("expected for " + bad);
            } catch (ToolException e) {
                assertTrue("expected '[30, 3600]' in message, was: " + e.getMessage(),
                    e.getMessage().contains("[30, 3600]"));
            }
        }
    }

    /**
     * Каталог результатов рос без ограничений: каждый прогон оставляет .json и .1cv8.log,
     * и никто их не убирал. Свежие файлы трогать нельзя — они могут принадлежать
     * параллельному прогону.
     */
    @Test
    public void allocateResultFile_removesStaleLeftoversButKeepsFreshOnes() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "edt-mcp-test-results");
        Files.createDirectories(dir);
        Path stale = dir.resolve("stale-" + java.util.UUID.randomUUID() + ".json");
        Path fresh = dir.resolve("fresh-" + java.util.UUID.randomUUID() + ".json");
        Files.writeString(stale, "{}");
        Files.writeString(fresh, "{}");
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(
            java.time.Instant.now().minus(java.time.Duration.ofDays(30))));

        TestRunnerLauncher.allocateResultFile();

        assertTrue("свежий файл параллельного прогона трогать нельзя", Files.exists(fresh));
        assertTrue("протухший файл должен быть удалён", !Files.exists(stale));
        Files.deleteIfExists(fresh);
    }

    private static Path extractResultFile(List<String> cmd) throws Exception {
        for (String s : cmd) {
            if (s.contains("EDT_MCP_TESTS=")) {
                int idx = s.indexOf("rf=");
                if (idx >= 0) {
                    String b64 = s.substring(idx + 3);
                    int semi = b64.indexOf(';');
                    if (semi > 0) {
                        b64 = b64.substring(0, semi);
                    }
                    String path = new String(java.util.Base64.getDecoder().decode(b64),
                        java.nio.charset.StandardCharsets.UTF_8);
                    Path p = Path.of(path);
                    Files.createDirectories(p.getParent());
                    return p;
                }
            }
        }
        throw new IllegalStateException("rf= not found in command");
    }

    private static String base64(String s) {
        return java.util.Base64.getEncoder().encodeToString(
            s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String systemTempJson() {
        return System.getProperty("java.io.tmpdir") + "/edt-mcp-test-results/"
            + java.util.UUID.randomUUID() + ".json";
    }
}
