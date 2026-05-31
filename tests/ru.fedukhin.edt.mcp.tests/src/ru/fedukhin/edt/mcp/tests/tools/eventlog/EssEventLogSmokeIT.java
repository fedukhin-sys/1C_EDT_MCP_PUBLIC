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
 * Smoke test against the live ЕСС cluster log on this developer machine.
 *
 * <p>Skipped (via {@link org.junit.Assume}) on machines that don't have the
 * fixed log path — these assertions exercise the parser on a real, large,
 * actively-written {@code 1Cv8.lgf}/{@code *.lgp} pair.
 *
 * <p>If you ever rerun this on a fresh box, edit the constants to point at
 * whichever cluster IB uuid your ЕСС resolves to (find it via
 * {@code GetEventLogPathTool} or by reading {@code reg_1541/1CV8Clst.lst}).
 */
public class EssEventLogSmokeIT {

    private static final Path ESS_LOG = Paths.get(
        "C:\\Program Files (x86)\\1cv8\\srvinfo\\reg_1541\\04c66542-e27a-478f-b49f-8144e410edf2\\1Cv8Log");

    @Test
    public void scenario1_extensionApplyErrors_aprMay2026() throws Exception {
        assumeTrue("ESS log not present on this machine", Files.isDirectory(ESS_LOG));

        EventLogQuery q = new EventLogQuery().from("2026-04-01").to("2026-05-31T23:59:59");
        q.severity(List.of("Error"));
        q.commentContains = "ЕССКонтракты";
        q.limit = 100;

        EventLogReader.Page page = new EventLogReader().read(ESS_LOG, q);
        dump(Paths.get("E:\\EDTProjects\\EDT_MCP\\target\\smoke-scenario1.txt"), page, "scenario1");
        assertTrue("scanned > 0 expected on live ESS log", page.scanned > 0);
    }

    @Test
    public void scenario2_userFedukhinActivity() throws Exception {
        assumeTrue("ESS log not present on this machine", Files.isDirectory(ESS_LOG));

        EventLogQuery q = new EventLogQuery().from("2026-05-01").to("2026-05-31T23:59:59");
        q.user(List.of("ФедухинАА"));
        q.limit = 50;

        EventLogReader.Page page = new EventLogReader().read(ESS_LOG, q);
        dump(Paths.get("E:\\EDTProjects\\EDT_MCP\\target\\smoke-scenario2.txt"), page, "scenario2");
        assertTrue("scanned > 0 expected on live ESS log", page.scanned > 0);
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
