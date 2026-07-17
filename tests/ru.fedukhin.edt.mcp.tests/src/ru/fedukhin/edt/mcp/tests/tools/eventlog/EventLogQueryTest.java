package ru.fedukhin.edt.mcp.tests.tools.eventlog;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogQuery;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventRecord;

public class EventLogQueryTest {

    private static EventRecord rec(long date, String severity, String user, String event, String comment) {
        EventRecord r = new EventRecord();
        r.dateRaw = date;
        r.severityCode = severity;
        r.severity = severity == null ? null : (severity.equals("E") ? "Error" : "Information");
        r.user = user;
        r.event = event;
        r.comment = comment;
        return r;
    }

    @Test
    public void dateRange_filters() {
        EventLogQuery q = new EventLogQuery().from("2026-04-01").to("2026-05-31T23:59:59");
        var p = q.asPredicate();
        assertTrue(p.test(rec(20260415120000L, "I", "u", "e", "")));
        assertFalse(p.test(rec(20260301120000L, "I", "u", "e", "")));
        assertFalse(p.test(rec(20260601000000L, "I", "u", "e", "")));
    }

    @Test
    public void severity_normalises() {
        EventLogQuery q = new EventLogQuery();
        q.severity(List.of("Error"));
        var p = q.asPredicate();
        assertTrue(p.test(rec(20260415120000L, "E", "u", "e", "")));
        assertFalse(p.test(rec(20260415120000L, "I", "u", "e", "")));
    }

    @Test
    public void user_exactMatch() {
        EventLogQuery q = new EventLogQuery();
        q.user(List.of("ИвановИИ"));
        var p = q.asPredicate();
        assertTrue(p.test(rec(20260415120000L, "I", "ИвановИИ", "e", "")));
        assertFalse(p.test(rec(20260415120000L, "I", "Other", "e", "")));
        assertFalse(p.test(rec(20260415120000L, "I", null, "e", "")));
    }

    @Test
    public void commentContains_caseInsensitive() {
        EventLogQuery q = new EventLogQuery();
        q.commentContains = "демоРасширение";
        var p = q.asPredicate();
        assertTrue(p.test(rec(20260415120000L, "E", "u", "_$InfoBase$_.ConfigExtensionApplyError",
            "Расширение ДемоРасширение не применилось")));
        assertFalse(p.test(rec(20260415120000L, "E", "u", "x", "Другое расширение")));
    }

    @Test
    public void canonicalSeverity_acceptsManySpellings() {
        org.junit.Assert.assertEquals("E", EventLogQuery.canonicalSeverity("Error"));
        org.junit.Assert.assertEquals("E", EventLogQuery.canonicalSeverity("error"));
        org.junit.Assert.assertEquals("E", EventLogQuery.canonicalSeverity("e"));
        org.junit.Assert.assertEquals("W", EventLogQuery.canonicalSeverity("Warning"));
        org.junit.Assert.assertEquals("I", EventLogQuery.canonicalSeverity("Information"));
    }
}
