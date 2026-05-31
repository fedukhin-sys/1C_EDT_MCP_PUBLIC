package ru.fedukhin.edt.mcp.tools.eventlog.internal;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Immutable filter spec for {@link EventLogReader}. Builder-style mutators return
 * {@code this} for fluent assembly inside {@code QueryEventLogTool}.
 */
public final class EventLogQuery {

    public long dateFromRaw = 0;          // YYYYMMDDhhmmss; 0 = unbounded
    public long dateToRaw   = 99999999999999L;
    public Set<String> severities = Collections.emptySet();   // I/W/E/N (normalised)
    public Set<String> users = Collections.emptySet();
    public Set<String> userUuids = Collections.emptySet();
    public Set<String> applications = Collections.emptySet();
    public Set<String> events = Collections.emptySet();
    public String eventContains;
    public String commentContains;
    public String metadataContains;
    public Set<Long> sessions = Collections.emptySet();

    public int limit = 100;
    public int offset = 0;
    public boolean descending = false;

    public EventLogQuery from(String iso) { this.dateFromRaw = LgpParser.fromIso(iso); return this; }
    public EventLogQuery to(String iso)   { this.dateToRaw   = LgpParser.fromIso(iso); return this; }

    public EventLogQuery severity(List<String> vals) {
        this.severities = normalise(vals, EventLogQuery::canonicalSeverity);
        return this;
    }
    public EventLogQuery user(List<String> vals)        { this.users = normalise(vals, null); return this; }
    public EventLogQuery userUuid(List<String> vals)    { this.userUuids = normalise(vals, s -> s.toLowerCase(Locale.ROOT)); return this; }
    public EventLogQuery application(List<String> vals) { this.applications = normalise(vals, null); return this; }
    public EventLogQuery event(List<String> vals)       { this.events = normalise(vals, null); return this; }

    public EventLogQuery session(List<Long> vals) {
        if (vals == null || vals.isEmpty()) { this.sessions = Collections.emptySet(); return this; }
        Set<Long> s = new HashSet<>(vals);
        s.remove(null);
        this.sessions = s;
        return this;
    }

    /** Translates user-facing severity input ("Error", "warning", "I", …) to one-letter codes. */
    public static String canonicalSeverity(String s) {
        if (s == null) return null;
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "i": case "info": case "information": return "I";
            case "w": case "warn": case "warning": return "W";
            case "e": case "error":  return "E";
            case "n": case "note":   return "N";
            default: return s; // pass-through; matcher will fail-closed
        }
    }

    private static Set<String> normalise(List<String> vals, java.util.function.Function<String, String> tx) {
        if (vals == null || vals.isEmpty()) return Collections.emptySet();
        Set<String> s = new HashSet<>();
        for (String v : vals) {
            if (v == null) continue;
            s.add(tx == null ? v : tx.apply(v));
        }
        return s;
    }

    public Predicate<EventRecord> asPredicate() {
        return ev -> {
            if (ev.dateRaw < dateFromRaw || ev.dateRaw > dateToRaw) return false;
            if (!severities.isEmpty() && !severities.contains(ev.severityCode)) return false;
            if (!users.isEmpty() && (ev.user == null || !users.contains(ev.user))) return false;
            if (!userUuids.isEmpty()) {
                String uu = ev.userUuid == null ? null : ev.userUuid.toLowerCase(Locale.ROOT);
                if (uu == null || !userUuids.contains(uu)) return false;
            }
            if (!applications.isEmpty() && (ev.application == null || !applications.contains(ev.application))) return false;
            if (!events.isEmpty() && (ev.event == null || !events.contains(ev.event))) return false;
            if (eventContains != null && !eventContains.isEmpty()) {
                if (ev.event == null || !ev.event.toLowerCase(Locale.ROOT).contains(eventContains.toLowerCase(Locale.ROOT))) return false;
            }
            if (commentContains != null && !commentContains.isEmpty()) {
                if (ev.comment == null || !ev.comment.toLowerCase(Locale.ROOT).contains(commentContains.toLowerCase(Locale.ROOT))) return false;
            }
            if (metadataContains != null && !metadataContains.isEmpty()) {
                if (ev.metadata == null || !ev.metadata.toLowerCase(Locale.ROOT).contains(metadataContains.toLowerCase(Locale.ROOT))) return false;
            }
            if (!sessions.isEmpty() && !sessions.contains(ev.session)) return false;
            return true;
        };
    }
}
