package ru.fedukhin.edt.mcp.tools.eventlog.internal;

import java.util.List;

/**
 * Token produced by {@link LgTokenizer}. A 1Cv8 log "record" is a tree of these:
 * either a primitive ({@link #ATOM}, {@link #STRING}) or a list of children
 * ({@link #LIST}, the children parsed from a {@code {...}} group).
 */
public final class LgToken {

    public enum Kind { ATOM, STRING, LIST }

    public final Kind kind;
    public final String text;        // raw ATOM (no quotes) or unescaped STRING; null for LIST
    public final List<LgToken> items; // null unless LIST

    private LgToken(Kind k, String t, List<LgToken> i) {
        this.kind = k;
        this.text = t;
        this.items = i;
    }

    public static LgToken atom(String s)   { return new LgToken(Kind.ATOM, s, null); }
    public static LgToken string(String s) { return new LgToken(Kind.STRING, s, null); }
    public static LgToken list(List<LgToken> items) { return new LgToken(Kind.LIST, null, items); }

    public boolean isAtom()   { return kind == Kind.ATOM; }
    public boolean isString() { return kind == Kind.STRING; }
    public boolean isList()   { return kind == Kind.LIST; }

    public String asString() {
        if (kind == Kind.STRING) return text;
        if (kind == Kind.ATOM)   return text;
        return null;
    }

    public Long asLong() {
        if (kind != Kind.ATOM) return null;
        try { return Long.parseLong(text); } catch (NumberFormatException e) { return null; }
    }
}
