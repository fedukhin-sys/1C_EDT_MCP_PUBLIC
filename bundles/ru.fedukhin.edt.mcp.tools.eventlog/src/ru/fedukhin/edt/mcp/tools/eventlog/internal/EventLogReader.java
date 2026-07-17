package ru.fedukhin.edt.mcp.tools.eventlog.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Orchestrates a full query: loads {@code 1Cv8.lgf} references, walks {@code *.lgp}
 * partitions in date order (skipping those clearly outside the query window), and
 * returns matching {@link EventRecord}s respecting {@code limit}/{@code offset}.
 *
 * <p>Bounds CPU on huge logs via {@link #DEFAULT_TOTAL_SCAN_CAP}: once total
 * scanned-record count exceeds the cap, iteration stops and {@link Page#truncated}
 * is set, so the caller can warn the user.
 */
public final class EventLogReader {

    public static final int DEFAULT_TOTAL_SCAN_CAP = 1_000_000;

    public static final class Page {
        public final List<EventRecord> records;
        public final long matchedTotal;
        public final long scanned;
        public final boolean truncated;
        /** Хотя бы одна партиция оборвалась посреди записи (штатно для активной партиции). */
        public final boolean partial;
        public Page(List<EventRecord> r, long total, long scanned, boolean truncated) {
            this(r, total, scanned, truncated, false);
        }
        public Page(List<EventRecord> r, long total, long scanned, boolean truncated, boolean partial) {
            this.records = r; this.matchedTotal = total; this.scanned = scanned;
            this.truncated = truncated; this.partial = partial;
        }
    }

    private final LgfParser lgfParser;
    private final int totalScanCap;

    public EventLogReader() { this(new LgfParser(), DEFAULT_TOTAL_SCAN_CAP); }
    public EventLogReader(LgfParser lgfParser, int totalScanCap) {
        this.lgfParser = lgfParser;
        this.totalScanCap = totalScanCap;
    }

    public Page read(Path logDir, EventLogQuery q) throws ToolException {
        if (!Files.isDirectory(logDir)) {
            throw new ToolException("event log directory not found: " + logDir);
        }
        Path lgf = logDir.resolve("1Cv8.lgf");
        if (!Files.isRegularFile(lgf)) {
            throw new ToolException("references file missing: " + lgf);
        }
        EventLogReferences refs;
        try {
            refs = lgfParser.parse(lgf);
        } catch (IOException e) {
            throw new ToolException("failed to parse " + lgf + ": " + e.getMessage(), e);
        }

        List<Path> partitions = listPartitionsInRange(logDir, q.dateFromRaw, q.dateToRaw, q.descending);
        LgpParser lgp = new LgpParser(refs);

        List<EventRecord> matches = new ArrayList<>();
        long[] matchedCounter = { 0 };
        long stopMatchedAt = (long) q.offset + (long) q.limit;
        long scanned = 0;
        boolean truncated = false;
        boolean partial = false;

        for (Path part : partitions) {
            // Для descending окно нельзя применять на лету: порядок сканирования — по возрастанию
            // даты внутри партиции, а нужны самые новые записи. Поэтому по партиции держим хвост
            // длиной не больше offset+limit — этого всегда достаточно, а память не растёт с логом.
            java.util.Deque<EventRecord> partitionTail = q.descending ? new java.util.ArrayDeque<>() : null;
            try {
                LgpParser.StreamOutcome outcome = lgp.stream(part, q.asPredicate(), ev -> {
                    long ord = matchedCounter[0]++;
                    if (q.descending) {
                        partitionTail.addLast(ev);
                        if (partitionTail.size() > stopMatchedAt) {
                            partitionTail.removeFirst();
                        }
                    } else if (ord >= q.offset && ord < stopMatchedAt) {
                        matches.add(ev);
                    }
                    return true;
                });
                scanned += outcome.total();
                partial |= outcome.partial();
            } catch (IOException e) {
                throw new ToolException("failed to read " + part + ": " + e.getMessage(), e);
            }
            if (partitionTail != null) {
                matches.addAll(partitionTail);
            }
            if (scanned > totalScanCap) {
                truncated = true;
                break;
            }
        }
        List<EventRecord> page = matches;
        if (q.descending) {
            // Партиции перечислены newest-first, а записи внутри них — oldest-first, поэтому
            // сортируем собранное по самой дате: так порядок верен и при перекрытии партиций.
            matches.sort(Comparator.comparingLong((EventRecord r) -> r.dateRaw).reversed());
            int from = Math.min(q.offset, matches.size());
            int to   = (int) Math.min((long) from + q.limit, matches.size());
            page = new ArrayList<>(matches.subList(from, to));
        }
        return new Page(page, matchedCounter[0], scanned, truncated, partial);
    }

    public static List<Path> listPartitionsInRange(Path logDir, long fromRaw, long toRaw, boolean descending) throws ToolException {
        List<Path> all = new ArrayList<>();
        try (var stream = Files.list(logDir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".lgp"))
                  .forEach(all::add);
        } catch (IOException e) {
            throw new ToolException("failed to list log dir: " + e.getMessage(), e);
        }
        all.sort(Comparator.comparing(p -> p.getFileName().toString()));
        // Filter to partitions overlapping [fromRaw, toRaw]:
        //   - LAST partition with start <= fromDay (it may contain events from fromDay onward)
        //   - ANY partition with start in [fromDay, toDay)
        long fromDay = (fromRaw / 1000000L) * 1000000L;
        long toDay   = (toRaw   / 1000000L + 1) * 1000000L;
        List<Path> filtered = new ArrayList<>();
        int firstIdx = -1;
        for (int i = 0; i < all.size(); i++) {
            String name = all.get(i).getFileName().toString();
            long startRaw;
            try { startRaw = Long.parseLong(name.substring(0, 14)); }
            catch (RuntimeException e) { continue; }
            if (startRaw <= fromDay) firstIdx = i;
            if (startRaw >= toDay) break;
            if (startRaw >= fromDay) filtered.add(all.get(i));
        }
        if (firstIdx >= 0) {
            Path candidate = all.get(firstIdx);
            if (filtered.isEmpty() || !filtered.get(0).equals(candidate)) {
                filtered.add(0, candidate);
            }
        }
        if (descending) Collections.reverse(filtered);
        return filtered;
    }
}
