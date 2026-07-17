package ru.fedukhin.edt.mcp.tests.tools.eventlog;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogQuery;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogReader;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventRecord;

/**
 * Smoke test against a live cluster event log on a developer machine.
 *
 * <p>Skipped (via {@link org.junit.Assume}) unless the log directory is provided — these
 * assertions exercise the parser on a real, large, actively-written {@code 1Cv8.lgf}/{@code *.lgp}
 * pair, which no CI box has.
 *
 * <p>Everything machine- and client-specific is passed via system properties so nothing
 * identifying lands in the repository (this file ships to a public mirror). To run it, point
 * the parser at whichever cluster IB your log resolves to — find the uuid via
 * {@code GetEventLogPathTool} or {@code reg_1541/1CV8Clst.lst}:
 *
 * <pre>
 *   -Dedt.mcp.eventlog.smoke.dir="C:\…\srvinfo\reg_1541\&lt;ib-uuid&gt;\1Cv8Log"
 *   -Dedt.mcp.eventlog.smoke.comment=&lt;substring of a comment to match&gt;   (optional)
 *   -Dedt.mcp.eventlog.smoke.user=&lt;user name to filter&gt;                  (optional)
 *   -Dedt.mcp.eventlog.smoke.out=&lt;dir for dumps&gt;                         (optional, default: target/)
 * </pre>
 */
public class EssEventLogSmokeIT {

    /** Log directory; when unset the tests skip. No default — a hardcoded path would leak a machine. */
    private static final String LOG_DIR  = System.getProperty("edt.mcp.eventlog.smoke.dir");
    private static final String COMMENT  = System.getProperty("edt.mcp.eventlog.smoke.comment");
    private static final String USER     = System.getProperty("edt.mcp.eventlog.smoke.user");
    private static final Path   OUT_DIR  = Paths.get(
            System.getProperty("edt.mcp.eventlog.smoke.out", "target"));

    private static Path logDir() {
        return LOG_DIR == null ? null : Paths.get(LOG_DIR);
    }

    @Test
    public void scenario1_commentFilteredErrors() throws Exception {
        Path log = logDir();
        assumeTrue("set -Dedt.mcp.eventlog.smoke.dir to a live log directory to run this smoke",
            log != null && Files.isDirectory(log));
        assumeTrue("set -Dedt.mcp.eventlog.smoke.comment to a comment substring", COMMENT != null);

        EventLogQuery q = new EventLogQuery().from("2026-04-01").to("2026-05-31T23:59:59");
        q.severity(List.of("Error"));
        q.commentContains = COMMENT;
        q.limit = 100;

        EventLogReader.Page page = new EventLogReader().read(log, q);
        dump(OUT_DIR.resolve("smoke-scenario1.txt"), page, "scenario1");
        assertTrue("scanned > 0 expected on live log", page.scanned > 0);
    }

    @Test
    public void scenario2_userFilteredActivity() throws Exception {
        Path log = logDir();
        assumeTrue("set -Dedt.mcp.eventlog.smoke.dir to a live log directory to run this smoke",
            log != null && Files.isDirectory(log));
        assumeTrue("set -Dedt.mcp.eventlog.smoke.user to a user name", USER != null);

        EventLogQuery q = new EventLogQuery().from("2026-05-01").to("2026-05-31T23:59:59");
        q.user(List.of(USER));
        q.limit = 50;

        EventLogReader.Page page = new EventLogReader().read(log, q);
        dump(OUT_DIR.resolve("smoke-scenario2.txt"), page, "scenario2");
        assertTrue("scanned > 0 expected on live log", page.scanned > 0);
    }

    private static void dump(Path file, EventLogReader.Page page, String tag) throws Exception {
        Files.createDirectories(file.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("[" + tag + "] scanned=" + page.scanned
                + " matched=" + page.matchedTotal
                + " returned=" + page.records.size()
                + " truncated=" + page.truncated);
            w.newLine();
            for (EventRecord ev : page.records) {
                w.write(ev.dateIso + " sess=" + ev.session + " conn=" + ev.connectionId
                    + " user=" + ev.user + " app=" + ev.application + " server=" + ev.server
                    + " event=" + ev.event + " severity=" + ev.severity
                    + " metadata=" + ev.metadata
                    + " present=" + truncate(ev.dataPresentation, 120)
                    + " comment=" + truncate(ev.comment, 200));
                w.newLine();
            }
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
