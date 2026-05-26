package ru.fedukhin.edt.mcp.tools.testrun.internal;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Launches 1cv8.exe ENTERPRISE with the encoded selector, waits for the result
 * file to be produced by the BSL handler, parses it into {@link TestRunResult}.
 * Owns a single-thread daemon executor (Stage 7d pattern).
 */
@Singleton
public class TestRunnerLauncher {

    public static final int MIN_TIMEOUT_SECONDS = 30;
    public static final int MAX_TIMEOUT_SECONDS = 3600;
    private static final long SHUTDOWN_WAIT_SEC = 5;

    /** Abstracted process launcher — real impl uses ProcessBuilder; tests inject a fake. */
    public interface ProcessRunner {
        record RunOutcome(int exitCode, String stdout, String stderr) { }
        RunOutcome run(List<String> command, Map<String, String> env, int timeoutSeconds) throws Exception;
    }

    private final ProcessRunner runner;
    private volatile ExecutorService executor;

    @Inject
    public TestRunnerLauncher(ProcessRunner runner) {
        this.runner = runner;
    }

    public TestRunResult runWithTimeout(String exe1cv8, String ibConnection,
                                         String startupParam, int timeoutSeconds) throws ToolException {
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new ToolException("timeoutSeconds must be in ["
                + MIN_TIMEOUT_SECONDS + ", " + MAX_TIMEOUT_SECONDS + "], got " + timeoutSeconds);
        }
        Path resultFile = resolveResultFile(startupParam);
        Path outLog = resultFile.resolveSibling(resultFile.getFileName().toString().replace(".json", ".1cv8.log"));
        List<String> cmd = buildCommand(exe1cv8, ibConnection, startupParam, outLog);
        ExecutorService exec = ensureExecutor();
        Future<ProcessRunner.RunOutcome> f = exec.submit(() -> runner.run(cmd, Map.of(), timeoutSeconds));
        ProcessRunner.RunOutcome outcome;
        try {
            outcome = f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new ToolException("test run timeout after " + timeoutSeconds
                + "s; process killed; partial results may have been written but are not loaded");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new ToolException("test run failed: "
                + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw new ToolException("test run interrupted");
        }
        if (!Files.exists(resultFile)) {
            String stderr = outcome.stderr() == null ? "" : outcome.stderr();
            String stderrTail = stderr.length() > 500 ? stderr.substring(stderr.length() - 500) : stderr;
            String stdout = outcome.stdout() == null ? "" : outcome.stdout();
            String stdoutTail = stdout.length() > 500 ? stdout.substring(stdout.length() - 500) : stdout;
            String outLogContent = "";
            try {
                if (Files.exists(outLog)) {
                    String log = Files.readString(outLog);
                    outLogContent = log.length() > 1000 ? log.substring(log.length() - 1000) : log;
                }
            } catch (IOException ignored) { /* best effort */ }
            throw new ToolException("test run failed: no result file produced "
                + "(exit=" + outcome.exitCode()
                + ", cmd=" + cmd
                + ", stderr tail: " + stderrTail
                + ", stdout tail: " + stdoutTail
                + ", 1cv8 /Out tail: " + outLogContent + ")");
        }
        String json;
        try {
            json = Files.readString(resultFile);
        } catch (IOException e) {
            throw new ToolException("test run result file unreadable: " + e.getMessage(), e);
        }
        return TestResultParser.parse(json);
    }

    public synchronized void shutdown() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_WAIT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    private synchronized ExecutorService ensureExecutor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "edt-mcp-test-runner");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    private static List<String> buildCommand(String exe1cv8, String ibConnection, String startupParam,
                                             Path outLog) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe1cv8);
        cmd.add("ENTERPRISE");
        // Java ProcessBuilder on Windows escapes embedded " as \", which 1cv8.exe does NOT
        // recognize in /IBConnectionString File="..." form — 1cv8 returns "Неопределена
        // информационная база". Parse the connection string into /F<path> or /S<srvr>\<ref>
        // shortcuts, which take simple unquoted values.
        appendIbArgs(cmd, ibConnection);
        cmd.add("/C");
        cmd.add(startupParam);
        cmd.add("/DisableStartupMessages");
        cmd.add("/DisableStartupDialogs");
        cmd.add("/IBCheckAndRepair-");
        if (outLog != null) {
            cmd.add("/Out");
            cmd.add(outLog.toString());
        }
        return cmd;
    }

    /**
     * Translates a 1С connection string (forms {@code File="<path>";} or
     * {@code Srvr="<server>";Ref="<db>";}) into the {@code /F} / {@code /S}
     * shortcut command-line arguments that don't require embedded quote
     * escaping. Falls back to {@code /IBConnectionString} for unrecognised
     * forms, so the call still launches even if a new connection-string flavour
     * appears.
     */
    public static void appendIbArgs(List<String> cmd, String ibConnection) {
        String file = extractValue(ibConnection, "File");
        if (file != null) {
            cmd.add("/F" + file);
            return;
        }
        String server = extractValue(ibConnection, "Srvr");
        String ref = extractValue(ibConnection, "Ref");
        if (server != null && ref != null) {
            cmd.add("/S" + server + "\\" + ref);
            return;
        }
        cmd.add("/IBConnectionString");
        cmd.add(ibConnection);
    }

    /** Extracts the value of {@code key="value"} (or unquoted {@code key=value;}) from a 1С connection string. */
    private static String extractValue(String connection, String key) {
        if (connection == null) return null;
        int kIdx = connection.indexOf(key + "=");
        if (kIdx < 0) return null;
        int start = kIdx + key.length() + 1;
        if (start >= connection.length()) return null;
        if (connection.charAt(start) == '"') {
            int end = connection.indexOf('"', start + 1);
            if (end < 0) return null;
            return connection.substring(start + 1, end);
        }
        int end = connection.indexOf(';', start);
        if (end < 0) end = connection.length();
        return connection.substring(start, end);
    }

    private static Path resolveResultFile(String startupParam) throws ToolException {
        int idx = startupParam.indexOf("rf=");
        if (idx < 0) throw new ToolException("startup param has no rf= component");
        String tail = startupParam.substring(idx + 3);
        int semi = tail.indexOf(';');
        if (semi > 0) tail = tail.substring(0, semi);
        try {
            String path = new String(Base64.getDecoder().decode(tail), StandardCharsets.UTF_8);
            return Path.of(path);
        } catch (IllegalArgumentException e) {
            throw new ToolException("startup param rf= is not valid base64", e);
        }
    }

    /** Allocates a unique result-file path under the system temp dir. */
    public static Path allocateResultFile() throws ToolException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "edt-mcp-test-results");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ToolException("cannot create result temp dir: " + e.getMessage(), e);
        }
        return dir.resolve(UUID.randomUUID() + ".json");
    }
}
