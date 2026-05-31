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

/**
 * Parses {@code 1Cv8.lgf} (event log reference tables) into an {@link EventLogReferences}.
 *
 * <p>File layout (header):
 * <pre>
 *   1CV8LOG(ver 2.0)
 *   &lt;file-uuid&gt;
 *
 *   {1,&lt;userUuid&gt;,"&lt;name&gt;",&lt;seq&gt;},
 *   {2,"&lt;computer&gt;",&lt;seq&gt;},
 *   ...
 * </pre>
 * UTF-8 (with leading BOM). 1C uses {@code ""} as escaped double-quote inside strings.
 *
 * <p>{@link #parse(Path)} opens the file with {@code FileShare.ReadWrite} semantics
 * (Java NIO default on Windows) so it works even while the cluster server is actively
 * writing to the log.
 */
public final class LgfParser {

    public EventLogReferences parse(Path lgf) throws IOException {
        try (InputStream in = Files.newInputStream(lgf, StandardOpenOption.READ);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return parse(br);
        }
    }

    public EventLogReferences parse(BufferedReader br) throws IOException {
        EventLogReferences out = new EventLogReferences();
        // Header: line 1 = "1CV8LOG(ver 2.0)" (may include UTF-8 BOM), line 2 = file uuid, line 3 = blank.
        skipBomAndHeader(br);
        LgTokenizer tk = new LgTokenizer(br);
        LgToken rec;
        while ((rec = tk.nextRecord()) != null) {
            consume(rec, out);
        }
        return out;
    }

    private void skipBomAndHeader(BufferedReader br) throws IOException {
        br.mark(8);
        int first = br.read();
        if (first != 0xFEFF) {
            // not BOM — push back via mark/reset
            br.reset();
        }
        // skip header lines until we find one starting with '{'
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

    private void consume(LgToken rec, EventLogReferences out) {
        if (!rec.isList() || rec.items.isEmpty()) return;
        LgToken head = rec.items.get(0);
        Long typeBoxed = head.asLong();
        if (typeBoxed == null) return;
        int type = typeBoxed.intValue();
        List<LgToken> items = rec.items;
        switch (type) {
            case 1: { // User: {1, UUID, "name", seq}
                if (items.size() >= 4) {
                    String uuid = items.get(1).asString();
                    String name = items.get(2).asString();
                    Integer seq = parseTrailingSeq(items);
                    if (seq != null) out.users.put(seq, new EventLogReferences.User(uuid, name));
                }
                break;
            }
            case 2: { // Computer: {2, "name", seq}
                if (items.size() >= 3) {
                    String name = items.get(1).asString();
                    Integer seq = parseTrailingSeq(items);
                    if (seq != null) out.computers.put(seq, name);
                }
                break;
            }
            case 3: { // Application: {3, "name", seq}
                if (items.size() >= 3) {
                    String name = items.get(1).asString();
                    Integer seq = parseTrailingSeq(items);
                    if (seq != null) out.applications.put(seq, name);
                }
                break;
            }
            case 4: { // Event: {4, "name", seq}
                if (items.size() >= 3) {
                    String name = items.get(1).asString();
                    Integer seq = parseTrailingSeq(items);
                    if (seq != null) out.events.put(seq, name);
                }
                break;
            }
            case 5: { // Metadata: {5, UUID, "fullName", seq}
                if (items.size() >= 4) {
                    String uuid = items.get(1).asString();
                    String fullName = items.get(2).asString();
                    Integer seq = parseTrailingSeq(items);
                    if (seq != null) out.metadata.put(seq, new EventLogReferences.Metadata(uuid, fullName));
                }
                break;
            }
            case 6: { // WorkServer: {6, "name", seq}
                if (items.size() >= 3) {
                    String name = items.get(1).asString();
                    Integer seq = parseTrailingSeq(items);
                    if (seq != null) out.servers.put(seq, name);
                }
                break;
            }
            case 7: { // MainPort: {7, port, seq}
                if (items.size() >= 3) {
                    Long port = items.get(1).asLong();
                    Integer seq = parseTrailingSeq(items);
                    if (seq != null && port != null) out.mainPorts.put(seq, port.intValue());
                }
                break;
            }
            case 8: { // SecondaryPort
                if (items.size() >= 3) {
                    Long port = items.get(1).asLong();
                    Integer seq = parseTrailingSeq(items);
                    if (seq != null && port != null) out.secondaryPorts.put(seq, port.intValue());
                }
                break;
            }
            default:
                // 9 (DataSeparator), 10, 11, 12, 13 — not yet consumed; safe to ignore.
                break;
        }
    }

    private Integer parseTrailingSeq(List<LgToken> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Long v = items.get(i).asLong();
            if (v != null) return v.intValue();
        }
        return null;
    }
}
