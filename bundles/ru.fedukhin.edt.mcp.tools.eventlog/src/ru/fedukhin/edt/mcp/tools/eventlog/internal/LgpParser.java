package ru.fedukhin.edt.mcp.tools.eventlog.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Predicate;

/**
 * Streams events from one {@code *.lgp} partition file, resolving reference
 * indices against a pre-loaded {@link EventLogReferences}.
 *
 * <p>Each record is a {@code {…}} list with the following positional fields
 * (1-based, as documented by 1C / verified empirically on 8.3 server logs):
 * <pre>
 *   1  dateRaw         YYYYMMDDhhmmss (decimal)
 *   2  txStatus        N|C|U|R (one-letter)
 *   3  txId            {hexTimestamp, hexId}
 *   4  user            int  -> users[seq].name
 *   5  computer        int  -> computers[seq]
 *   6  application     int  -> applications[seq]
 *   7  connectionId    int
 *   8  event           int  -> events[seq]
 *   9  severity        I|W|E|N
 *  10  comment         string
 *  11  metadata        int  -> metadata[seq]
 *  12  data            {"type", value}
 *  13  dataPresentation string
 *  14  server          int  -> servers[seq]
 *  15  mainPort        int  -> mainPorts[seq]
 *  16  secondaryPort   int  -> secondaryPorts[seq]
 *  17  session         int
 * </pre>
 * Trailing fields after 17 are tolerated and ignored.
 */
public final class LgpParser {

    @FunctionalInterface
    public interface Sink {
        /** Receives one event; return {@code false} to stop iteration early. */
        boolean accept(EventRecord rec);
    }

    private final EventLogReferences refs;

    public LgpParser(EventLogReferences refs) {
        this.refs = refs;
    }

    /** Streams all events from {@code lgp}. Returns total records parsed (incl. skipped). */
    public long stream(Path lgp, Predicate<EventRecord> filter, Sink sink) throws IOException {
        try (InputStream in = Files.newInputStream(lgp, StandardOpenOption.READ);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 64 * 1024)) {
            return stream(br, filter, sink);
        }
    }

    public long stream(BufferedReader br, Predicate<EventRecord> filter, Sink sink) throws IOException {
        skipHeader(br);
        LgTokenizer tk = new LgTokenizer(br);
        long total = 0;
        LgToken rec;
        while ((rec = tk.nextRecord()) != null) {
            total++;
            EventRecord ev = decode(rec);
            if (ev == null) continue;
            if (filter != null && !filter.test(ev)) continue;
            if (!sink.accept(ev)) break;
        }
        return total;
    }

    private void skipHeader(BufferedReader br) throws IOException {
        // First three lines are: "1CV8LOG(ver 2.0)" (with UTF-8 BOM), file-uuid, blank.
        br.mark(8);
        int first = br.read();
        if (first != 0xFEFF) br.reset();
        for (int i = 0; i < 5; i++) {
            br.mark(256);
            String line = br.readLine();
            if (line == null) return;
            String trimmed = line.trim();
            if (trimmed.startsWith("{")) {
                br.reset();
                return;
            }
        }
    }

    public EventRecord decode(LgToken rec) {
        if (!rec.isList() || rec.items.size() < 17) return null;
        List<LgToken> a = rec.items;
        EventRecord ev = new EventRecord();
        Long date = a.get(0).asLong();
        if (date == null) return null;
        ev.dateRaw = date;
        ev.dateIso = toIso(date);

        ev.txStatus = a.get(1).isAtom() ? a.get(1).text : null;

        // Field 3: txId = {hexTimestamp, hexId}
        if (a.get(2).isList() && a.get(2).items.size() == 2) {
            String h0 = a.get(2).items.get(0).asString();
            String h1 = a.get(2).items.get(1).asString();
            if (h0 != null && h1 != null && !(h0.equals("0") && h1.equals("0"))) {
                ev.txId = h0 + "/" + h1;
            }
        }

        ev.userSeq = intOf(a.get(3));
        ev.user = refs.userName(ev.userSeq);
        ev.userUuid = refs.userUuid(ev.userSeq);

        ev.computerSeq = intOf(a.get(4));
        ev.computer = refs.computers.get(ev.computerSeq);

        ev.applicationSeq = intOf(a.get(5));
        ev.application = refs.applications.get(ev.applicationSeq);

        Long conn = a.get(6).asLong();
        ev.connectionId = conn == null ? 0 : conn;

        ev.eventSeq = intOf(a.get(7));
        ev.event = refs.events.get(ev.eventSeq);

        ev.severityCode = a.get(8).isAtom() ? a.get(8).text : null;
        ev.severity = decodeSeverity(ev.severityCode);

        ev.comment = a.get(9).asString();

        ev.metadataSeq = intOf(a.get(10));
        ev.metadata = refs.metadataName(ev.metadataSeq);
        ev.metadataUuid = refs.metadataUuid(ev.metadataSeq);

        if (a.get(11).isList() && !a.get(11).items.isEmpty()) {
            ev.dataType = a.get(11).items.get(0).asString();
            if (a.get(11).items.size() > 1) {
                ev.dataValue = a.get(11).items.get(1).asString();
            }
        }
        ev.dataPresentation = a.get(12).asString();

        ev.serverSeq = intOf(a.get(13));
        ev.server = refs.servers.get(ev.serverSeq);

        ev.mainPortSeq = intOf(a.get(14));
        ev.mainPort = refs.mainPorts.get(ev.mainPortSeq);

        ev.secondaryPortSeq = intOf(a.get(15));
        ev.secondaryPort = refs.secondaryPorts.get(ev.secondaryPortSeq);

        Long session = a.get(16).asLong();
        ev.session = session == null ? 0 : session;

        return ev;
    }

    private static int intOf(LgToken t) {
        Long v = t.asLong();
        return v == null ? 0 : v.intValue();
    }

    public static String decodeSeverity(String code) {
        if (code == null) return null;
        switch (code) {
            case "I": return "Information";
            case "W": return "Warning";
            case "E": return "Error";
            case "N": return "Note";
            default:  return code;
        }
    }

    public static String decodeTxStatus(String code) {
        if (code == null) return null;
        switch (code) {
            case "N": return "NotTransactional";
            case "C": return "Committed";
            case "U": return "Unfinished";
            case "R": return "RolledBack";
            default:  return code;
        }
    }

    /** Decode {@code YYYYMMDDhhmmss} into ISO {@code 2026-04-06T12:34:56}. */
    public static String toIso(long ts) {
        int yyyy = (int) (ts / 10000000000L);
        int mm   = (int) ((ts / 100000000L) % 100);
        int dd   = (int) ((ts / 1000000L) % 100);
        int hh   = (int) ((ts / 10000L) % 100);
        int mi   = (int) ((ts / 100L) % 100);
        int ss   = (int) (ts % 100);
        return String.format("%04d-%02d-%02dT%02d:%02d:%02d", yyyy, mm, dd, hh, mi, ss);
    }

    /** Encode ISO datetime back to {@code YYYYMMDDhhmmss}. Accepts {@code 2026-04-06} (→ midnight). */
    public static long fromIso(String iso) {
        String s = iso.trim();
        if (s.length() == 10) s = s + "T00:00:00";
        // 2026-04-06T12:34:56
        int yyyy = Integer.parseInt(s.substring(0, 4));
        int mm   = Integer.parseInt(s.substring(5, 7));
        int dd   = Integer.parseInt(s.substring(8, 10));
        int hh   = Integer.parseInt(s.substring(11, 13));
        int mi   = Integer.parseInt(s.substring(14, 16));
        int ss   = Integer.parseInt(s.substring(17, 19));
        return (long) yyyy * 10000000000L
             + (long) mm   * 100000000L
             + (long) dd   * 1000000L
             + (long) hh   * 10000L
             + (long) mi   * 100L
             + (long) ss;
    }
}
