package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reflective formatter for an mcore {@code TypeDescription} → short type string.
 *
 * <p>Used by {@code get_md_object}/{@code list_attributes} (attribute types) and
 * {@code get_form} (form attribute {@code valueType}). Fixes BUG-03/BUG-04: the
 * previous code only inspected String/Number qualifiers and returned {@code ""}
 * (or a raw {@code TypeDescriptionImpl@hex} {@code toString()}) for
 * Date/Boolean/reference types.
 *
 * <p>The real type identity lives in {@code TypeDescription.getTypes()} — an
 * {@code EList<TypeItem>}; each {@code TypeItem} carries {@code getName()}
 * (English/canonical) and {@code getNameRu()}. Primitive type names are combined
 * with the matching qualifier (length / precision / scale); reference type names
 * (e.g. {@code CatalogRef.X}) are emitted verbatim.
 *
 * <p>All access is reflective so the {@code tools.md}/{@code tools.form} bundles
 * need no compile dependency on the concrete mcore implementation classes.
 */
public final class McoreTypeReader {

    private McoreTypeReader() {}

    /**
     * @param typeDescription an mcore {@code TypeDescription} (may be {@code null})
     * @return {@code String} for a single type, {@code List<String>} for a composite
     *         type, or {@code ""} when no type can be resolved
     */
    public static Object format(Object typeDescription) {
        if (typeDescription == null) {
            return "";
        }
        List<String> names = typeItemNames(typeDescription);
        Integer strLen = stringLength(typeDescription);
        int[] num = numberQualifiers(typeDescription);

        if (names.isEmpty()) {
            // No type items — fall back to qualifier-only inference (legacy behaviour).
            if (strLen != null) {
                return "String(" + strLen + ")";
            }
            if (num != null) {
                return num[1] > 0 ? "Number(" + num[0] + "," + num[1] + ")"
                                  : "Number(" + num[0] + ")";
            }
            return "";
        }

        List<String> out = new ArrayList<>(names.size());
        for (String n : names) {
            out.add(formatName(n, strLen, num));
        }
        return out.size() == 1 ? out.get(0) : out;
    }

    private static String formatName(String name, Integer strLen, int[] num) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "string":
            case "строка":
                return strLen != null ? "String(" + strLen + ")" : "String";
            case "number":
            case "число":
                if (num == null) {
                    return "Number";
                }
                return num[1] > 0 ? "Number(" + num[0] + "," + num[1] + ")"
                                  : "Number(" + num[0] + ")";
            case "date":
            case "дата":
                return "Date";
            case "boolean":
            case "булево":
                return "Boolean";
            default:
                // Reference type (CatalogRef.X, DocumentRef.Y, ...) — already qualified.
                return name;
        }
    }

    private static List<String> typeItemNames(Object td) {
        List<String> out = new ArrayList<>();
        Object list = invoke(td, "getTypes");
        if (list instanceof Iterable<?> items) {
            for (Object item : items) {
                Object n = invoke(item, "getName");
                if (n instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static Integer stringLength(Object td) {
        Object sq = invoke(td, "getStringQualifiers");
        Object len = invoke(sq, "getLength");
        if (len instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        return null;
    }

    /** @return {@code {precision, scale}} or {@code null} when there is no usable number qualifier. */
    private static int[] numberQualifiers(Object td) {
        Object nq = invoke(td, "getNumberQualifiers");
        if (nq == null) {
            return null;
        }
        Object p = invoke(nq, "getPrecision");
        Object s = invoke(nq, "getScale");
        int precision = p instanceof Number n ? n.intValue() : 0;
        int scale = s instanceof Number n ? n.intValue() : 0;
        return precision > 0 ? new int[] { precision, scale } : null;
    }

    private static Object invoke(Object obj, String method) {
        if (obj == null) {
            return null;
        }
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(method);
            // setAccessible: the resolved method may be declared on a non-public
            // class (EMF/BM impl or proxy) — invoking it otherwise throws.
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
