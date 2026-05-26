package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EObject;

/**
 * Вспомогательный класс для чтения коллекций attributes/dimensions/resources
 * у top-level MdObject. Используется в {@code GetMdObjectTool} и {@code ListAttributesTool}.
 *
 * <p>Логика по kind:
 * <ul>
 *   <li>Catalog, Document — только getAttributes (role="Attribute")</li>
 *   <li>InformationRegister, AccumulationRegister — getAttributes + getDimensions + getResources</li>
 *   <li>Остальные — пустой список</li>
 * </ul>
 */
public final class AttributeReader {

    private AttributeReader() {}

    public static List<Map<String, Object>> readAll(EObject parent, String kind,
                                                    TypeStringFormatter formatter) {
        List<Map<String, Object>> result = new ArrayList<>();
        switch (kind) {
            case "Catalog":
            case "Document":
                readList(parent, "getAttributes", "Attribute", formatter, result);
                break;
            case "InformationRegister":
            case "AccumulationRegister":
                readList(parent, "getAttributes", "Attribute", formatter, result);
                readList(parent, "getDimensions", "Dimension", formatter, result);
                readList(parent, "getResources",  "Resource",  formatter, result);
                break;
            default:
                // пустой список для остальных kinds
                break;
        }
        return result;
    }

    private static void readList(EObject parent, String accessor, String role,
                                 TypeStringFormatter formatter,
                                 List<Map<String, Object>> out) {
        Object listObj;
        try {
            listObj = parent.getClass().getMethod(accessor).invoke(parent);
        } catch (ReflectiveOperationException e) {
            return;
        }
        if (!(listObj instanceof Iterable)) return;
        for (Object item : (Iterable<?>) listObj) {
            if (!(item instanceof EObject)) continue;
            EObject child = (EObject) item;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name",    safeGetName(child));
            entry.put("role",    role);
            entry.put("type",    safeGetType(child, formatter));
            entry.put("synonym", safeGetSynonymDefault(child));
            entry.put("comment", safeGetComment(child));
            out.add(entry);
        }
    }

    private static String safeGetName(EObject obj) {
        try {
            Object n = obj.getClass().getMethod("getName").invoke(obj);
            return n instanceof String s ? s : "";
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    private static Object safeGetType(EObject obj, TypeStringFormatter formatter) {
        if (formatter == null) return "";
        try {
            Object td = obj.getClass().getMethod("getType").invoke(obj);
            if (td == null) return "";
            // TypeDescription → ParsedType через TypeStringParser не тривиален;
            // вернём строку-placeholder если нет getTypeName, иначе best-effort через
            // StringQualifiers и NumberQualifiers.
            return formatTypeDescription(td, formatter);
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    /**
     * Formats an mcore {@code TypeDescription} into a short type string.
     *
     * <p>BUG-03 fix: delegates to {@link McoreTypeReader}, which reads
     * {@code TypeDescription.getTypes()} and therefore also resolves
     * Date/Boolean/reference types. The previous implementation inspected only
     * String/Number qualifiers and returned {@code ""} for everything else.
     */
    private static Object formatTypeDescription(Object td, TypeStringFormatter formatter) {
        return McoreTypeReader.format(td);
    }

    @SuppressWarnings("unchecked")
    private static String safeGetSynonymDefault(EObject obj) {
        try {
            EMap<String, String> map = (EMap<String, String>) obj.getClass().getMethod("getSynonym").invoke(obj);
            if (map == null) return "";
            String val = map.get("ru");
            return val != null ? val : "";
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    private static String safeGetComment(EObject obj) {
        try {
            Object c = obj.getClass().getMethod("getComment").invoke(obj);
            return c instanceof String s ? s : "";
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }
}
