package ru.fedukhin.edt.mcp.tools.eventlog.internal;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming tokenizer for 1C event-log files ({@code 1Cv8.lgf}, {@code *.lgp}).
 *
 * <p>Format is a chain of top-level S-expression-like groups separated by commas:
 * each group is a {@code {…}} list whose elements are atoms (bare ints / identifiers
 * like {@code N}, {@code C}, {@code I}), strings ({@code "…"}, with {@code ""} as
 * escaped double-quote — 1C's convention), or nested {@code {…}} lists.
 *
 * <p>The tokenizer reads ONE top-level record per call to {@link #nextRecord()};
 * the surrounding {@code ,\n} between records is silently consumed. Returns
 * {@code null} at EOF.
 *
 * <p>UTF-8 with a leading BOM is handled by the caller (we accept a {@link Reader}).
 */
public final class LgTokenizer {

    private final Reader in;
    private int peeked = -2;
    private long pos = 0;

    public LgTokenizer(Reader in) {
        this.in = in;
    }

    public long pos() { return pos; }

    public LgToken nextRecord() throws IOException {
        skipWsAndCommas();
        int c = peek();
        if (c < 0) return null;
        if (c != '{') {
            throw new IOException("expected '{' at pos " + pos + " but got '" + (char) c + "'");
        }
        return readList();
    }

    private LgToken readList() throws IOException {
        expect('{');
        List<LgToken> items = new ArrayList<>();
        skipWs();
        if (peek() == '}') {
            read();
            return LgToken.list(items);
        }
        while (true) {
            skipWs();
            int c = peek();
            if (c < 0) throw new IOException("EOF inside list at pos " + pos);
            LgToken t;
            if (c == '{') {
                t = readList();
            } else if (c == '"') {
                t = readString();
            } else {
                t = readAtom();
            }
            items.add(t);
            skipWs();
            int n = read();
            if (n == ',') {
                // continue
            } else if (n == '}') {
                return LgToken.list(items);
            } else if (n < 0) {
                throw new IOException("EOF inside list, expected ',' or '}'");
            } else {
                throw new IOException("expected ',' or '}' at pos " + pos + " but got '" + (char) n + "'");
            }
        }
    }

    private LgToken readString() throws IOException {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = read();
            if (c < 0) throw new IOException("EOF inside string at pos " + pos);
            if (c == '"') {
                if (peek() == '"') { read(); sb.append('"'); continue; }
                return LgToken.string(sb.toString());
            }
            sb.append((char) c);
        }
    }

    private LgToken readAtom() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = peek();
            if (c < 0 || c == ',' || c == '}' || c == '{' || c == '"' || c == ' ' || c == '\r' || c == '\n' || c == '\t') {
                break;
            }
            sb.append((char) read());
        }
        return LgToken.atom(sb.toString());
    }

    private void skipWs() throws IOException {
        while (true) {
            int c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { read(); }
            else return;
        }
    }

    private void skipWsAndCommas() throws IOException {
        while (true) {
            int c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ',') { read(); }
            else return;
        }
    }

    private void expect(char ch) throws IOException {
        int c = read();
        if (c != ch) {
            throw new IOException("expected '" + ch + "' at pos " + pos + " but got " + (c < 0 ? "EOF" : "'" + (char) c + "'"));
        }
    }

    private int peek() throws IOException {
        if (peeked == -2) peeked = in.read();
        return peeked;
    }

    private int read() throws IOException {
        int c;
        if (peeked == -2) {
            c = in.read();
        } else {
            c = peeked;
            peeked = -2;
        }
        if (c >= 0) pos++;
        return c;
    }
}
