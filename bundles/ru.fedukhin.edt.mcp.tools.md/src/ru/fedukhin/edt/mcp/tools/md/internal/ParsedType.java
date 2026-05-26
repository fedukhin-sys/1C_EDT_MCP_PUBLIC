package ru.fedukhin.edt.mcp.tools.md.internal;

/**
 * Intermediate DTO между {@link TypeStringParser} и {@code AttributeFactory}:
 * хранит short-string представление в типизированной форме, не зависит от EDT-классов.
 * Конвертация в {@code TypeDescription} происходит позже (Plan 2).
 *
 * Допустимые состояния:
 *  - {@code STRING}: {@code length} optional;
 *  - {@code NUMBER}: {@code length} optional, {@code precision} optional (нужен если есть length);
 *  - {@code DATE}, {@code BOOLEAN}: без модификаторов;
 *  - {@code REF}: {@code refKind} ("Catalog"|"Document"|"Enum"), {@code refName}.
 */
public final class ParsedType {

    public enum Kind { STRING, NUMBER, DATE, BOOLEAN, REF, ANY_REF, UUID }

    private final Kind kind;
    private final Integer length;      // String/Number
    private final Integer precision;   // Number
    private final String  refKind;     // REF
    private final String  refName;     // REF

    private ParsedType(Kind kind, Integer length, Integer precision, String refKind, String refName) {
        this.kind = kind;
        this.length = length;
        this.precision = precision;
        this.refKind = refKind;
        this.refName = refName;
    }

    public static ParsedType string(Integer length)             { return new ParsedType(Kind.STRING, length, null, null, null); }
    public static ParsedType number(Integer length, Integer p)  { return new ParsedType(Kind.NUMBER, length, p, null, null); }
    public static ParsedType date()                             { return new ParsedType(Kind.DATE, null, null, null, null); }
    public static ParsedType bool()                             { return new ParsedType(Kind.BOOLEAN, null, null, null, null); }
    public static ParsedType ref(String refKind, String refName){ return new ParsedType(Kind.REF, null, null, refKind, refName); }
    public static ParsedType anyRef()                            { return new ParsedType(Kind.ANY_REF, null, null, null, null); }
    public static ParsedType uuid()                              { return new ParsedType(Kind.UUID, null, null, null, null); }

    public Kind    kind()      { return kind; }
    public Integer length()    { return length; }
    public Integer precision() { return precision; }
    public String  refKind()   { return refKind; }
    public String  refName()   { return refName; }
}
