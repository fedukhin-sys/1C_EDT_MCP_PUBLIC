package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.ArrayList;
import java.util.List;
import jakarta.inject.Singleton;

/**
 * Обратное к {@link TypeStringParser}: {@link ParsedType} → строковое представление.
 *
 * {@link #formatMany(List)} возвращает {@code String}, если в списке один тип,
 * иначе {@code List<String>}. JSON-сериализация в tools полагается на этот polymorphism
 * (см. JSON-схему {@code type: string | string[]} в spec §5).
 */
@Singleton
public class TypeStringFormatter {

    public String formatOne(ParsedType t) {
        switch (t.kind()) {
            case STRING:
                return (t.length() != null) ? "String(" + t.length() + ")" : "String";
            case NUMBER:
                if (t.length() == null) return "Number";
                return (t.precision() != null)
                        ? "Number(" + t.length() + "," + t.precision() + ")"
                        : "Number(" + t.length() + ")";
            case DATE:          return "Date";
            case BOOLEAN:       return "Boolean";
            case ANY_REF:       return "AnyRef";
            case UUID:          return "UUID";
            case VALUE_STORAGE: return "ValueStorage";
            case REF:           return t.refKind() + "Ref." + t.refName();
            default:            throw new IllegalStateException("unreachable: " + t.kind());
        }
    }

    /**
     * @return {@code String} при одном типе, {@code List<String>} при composite (≥2).
     */
    public Object formatMany(List<ParsedType> types) {
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("empty types list");
        }
        if (types.size() == 1) {
            return formatOne(types.get(0));
        }
        List<String> out = new ArrayList<>(types.size());
        for (ParsedType t : types) out.add(formatOne(t));
        return out;
    }
}
