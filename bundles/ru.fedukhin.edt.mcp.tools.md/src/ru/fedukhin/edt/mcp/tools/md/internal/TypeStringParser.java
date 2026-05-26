package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.inject.Singleton;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Преобразует short-string представление типа в {@link ParsedType}.
 *
 * <p>Принимаемые формы:
 * <ul>
 *   <li>{@code "String"} | {@code "String(N)"}</li>
 *   <li>{@code "Number"} | {@code "Number(N)"} | {@code "Number(N,M)"}</li>
 *   <li>{@code "Date"}</li>
 *   <li>{@code "Boolean"}</li>
 *   <li>{@code "<Catalog|Document|Enum>Ref.<Name>"}</li>
 * </ul>
 *
 * Расширения формата (длинные строки, BinaryQualifier, DateQualifier=Time only) — v2.
 */
@Singleton
public class TypeStringParser {

    private static final Pattern STRING_RE = Pattern.compile("^String(?:\\((\\d+)\\))?$");
    private static final Pattern NUMBER_RE = Pattern.compile("^Number(?:\\((\\d+)(?:,(\\d+))?\\))?$");
    private static final Pattern REF_RE    = Pattern.compile("^(Catalog|Document|Enum)Ref\\.([A-Za-zА-Яа-я_][A-Za-zА-Яа-я0-9_]*)$");

    public ParsedType parseOne(String s) throws ToolException {
        if (s == null || s.isEmpty()) {
            throw bad(s);
        }
        switch (s) {
            case "Date":    return ParsedType.date();
            case "Boolean": return ParsedType.bool();
            case "AnyRef":  return ParsedType.anyRef();
            case "UUID":    return ParsedType.uuid();
            default: // fall through
        }
        Matcher m = STRING_RE.matcher(s);
        if (m.matches()) {
            Integer len = (m.group(1) != null) ? Integer.parseInt(m.group(1)) : null;
            return ParsedType.string(len);
        }
        m = NUMBER_RE.matcher(s);
        if (m.matches()) {
            Integer len = (m.group(1) != null) ? Integer.parseInt(m.group(1)) : null;
            Integer pr  = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : null;
            return ParsedType.number(len, pr);
        }
        m = REF_RE.matcher(s);
        if (m.matches()) {
            return ParsedType.ref(m.group(1), m.group(2));
        }
        throw bad(s);
    }

    public List<ParsedType> parseMany(String[] arr) throws ToolException {
        if (arr == null || arr.length == 0) {
            throw new ToolException("type must be a non-empty string or non-empty array of strings");
        }
        List<ParsedType> out = new ArrayList<>(arr.length);
        for (String s : arr) {
            out.add(parseOne(s));
        }
        return out;
    }

    private static ToolException bad(String s) {
        return new ToolException(
            "cannot parse type string '" + s + "'; expected forms: "
            + "String[(N)], Number[(N[,M])], Date, Boolean, "
            + "CatalogRef.<Name>, DocumentRef.<Name>, EnumRef.<Name>");
    }
}
