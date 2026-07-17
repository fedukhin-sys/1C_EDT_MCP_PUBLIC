package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EObject;

/**
 * Вспомогательный класс для чтения коллекций attributes/dimensions/resources и табличных частей
 * у top-level MdObject. Используется в {@code GetMdObjectTool} и {@code ListAttributesTool}.
 *
 * <p>Логика {@link #readAll} по kind:
 * <ul>
 *   <li>Catalog, Document, BusinessProcess, Task, планы счетов/видов расчёта/видов характеристик,
 *       ExchangePlan — только getAttributes (role="Attribute")</li>
 *   <li>InformationRegister, AccumulationRegister — getAttributes + getDimensions + getResources</li>
 *   <li>Остальные — пустой список</li>
 * </ul>
 *
 * <p>Табличные части читаются отдельно ({@link #readTabularSections}) и в плоский список
 * реквизитов не подмешиваются.
 */
public final class AttributeReader {

    private AttributeReader() {}

    public static List<Map<String, Object>> readAll(EObject parent, String kind,
                                                    TypeStringFormatter formatter) {
        List<Map<String, Object>> result = new ArrayList<>();
        switch (kind) {
            case "Catalog":
            case "Document":
            case "BusinessProcess":
            case "Task":
            case "ChartOfAccounts":
            case "ChartOfCalculationTypes":
            case "ChartOfCharacteristicTypes":
            case "ExchangePlan":
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

    /**
     * Читает табличные части владельца и их колонки.
     *
     * <p>Рефлексивно, как и остальной BM-route: {@code getTabularSections()} объявлен на каждом
     * kind'е своим типом ({@code CatalogTabularSection}, {@code DocumentTabularSection}, …),
     * общего интерфейса-владельца в mdclass нет. У самой ТЧ колонки — {@code getAttributes()}.
     *
     * @return {@code [{name, synonym, attributes: [...]}]}; пустой список, если ТЧ нет либо
     *         у kind'а вовсе нет табличных частей
     */
    public static List<Map<String, Object>> readTabularSections(EObject parent,
                                                                TypeStringFormatter formatter) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object listObj;
        try {
            listObj = parent.getClass().getMethod("getTabularSections").invoke(parent);
        } catch (ReflectiveOperationException e) {
            return result; // kind без табличных частей
        }
        if (!(listObj instanceof Iterable)) return result;
        for (Object item : (Iterable<?>) listObj) {
            if (!(item instanceof EObject ts)) continue;
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("name",    safeGetName(ts));
            section.put("synonym", safeGetSynonymDefault(ts));
            List<Map<String, Object>> columns = new ArrayList<>();
            readList(ts, "getAttributes", "Attribute", formatter, columns);
            section.put("attributes", columns);
            result.add(section);
        }
        return result;
    }

    /**
     * Читает значения перечисления ({@code Enum#getEnumValues}).
     *
     * @return {@code [{name, synonym, comment}]}; пустой список, если владелец — не перечисление
     */
    public static List<Map<String, Object>> readEnumValues(EObject parent) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object listObj;
        try {
            listObj = parent.getClass().getMethod("getEnumValues").invoke(parent);
        } catch (ReflectiveOperationException e) {
            return result; // не Enum
        }
        if (!(listObj instanceof Iterable)) return result;
        for (Object item : (Iterable<?>) listObj) {
            if (!(item instanceof EObject value)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name",    safeGetName(value));
            entry.put("synonym", safeGetSynonymDefault(value));
            entry.put("comment", safeGetComment(value));
            result.add(entry);
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
