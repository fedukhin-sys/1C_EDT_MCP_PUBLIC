package ru.fedukhin.edt.mcp.tools.form.internal;

import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.tools.md.internal.McoreTypeReader;

/**
 * Рефлективный ридер дерева элементов формы, атрибутов и команд.
 *
 * Spike §9.1 amendments:
 * - Commands on Form: {@code getFormCommands()} (not {@code getCommands()}).
 * - Title: {@code EMap<String,String>} — call {@code .get("ru")} or fallback to first value.
 * - Items: {@code getItems()} on Form and any Group (FormItemContainer hierarchy).
 * - DataPath: {@code getDataPath()} on DataItem subtypes.
 * - Attribute type: {@code getValueType()} on AbstractFormAttribute.
 *
 * All reflective calls are best-effort: if a method doesn't exist or throws,
 * the corresponding field is simply omitted from the result map.
 */
@Singleton
public class FormReader {

    /**
     * Flat DFS list of items with "path" (slash-separated names from root).
     *
     * @param formObj the Form object (or AbstractForm — anything with {@code getItems()})
     */
    public List<Map<String, Object>> readItemsFlat(Object formObj) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<EObject> items = invokeItems(formObj);
        for (EObject child : items) {
            walkFlat(child, "", out);
        }
        return out;
    }

    private void walkFlat(EObject item, String parentPath, List<Map<String, Object>> out) {
        String name = (String) invokeOrNull(item, "getName");
        String path = (parentPath == null || parentPath.isEmpty())
                ? (name != null ? name : "")
                : parentPath + "/" + (name != null ? name : "");
        out.add(itemMap(item, path));
        for (EObject child : invokeItems(item)) {
            walkFlat(child, path, out);
        }
    }

    /**
     * Tree shape: root items have "children" with nested items.
     */
    public List<Map<String, Object>> readItemsTree(Object formObj) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EObject child : invokeItems(formObj)) {
            out.add(walkTree(child));
        }
        return out;
    }

    private Map<String, Object> walkTree(EObject item) {
        Map<String, Object> m = itemMap(item, (String) invokeOrNull(item, "getName"));
        List<EObject> children = invokeItems(item);
        if (!children.isEmpty()) {
            List<Map<String, Object>> kids = new ArrayList<>();
            for (EObject c : children) {
                kids.add(walkTree(c));
            }
            m.put("children", kids);
        }
        return m;
    }

    private static Map<String, Object> itemMap(EObject item, String nameOrPath) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", nameOrPath);
        m.put("name", invokeOrNull(item, "getName"));
        m.put("type", item.eClass() != null ? item.eClass().getName()
                                            : item.getClass().getSimpleName());
        Object dataPath = invokeOrNull(item, "getDataPath");
        if (dataPath != null) {
            m.put("dataPath", dataPath.toString());
        }
        return m;
    }

    /**
     * Read form-level attributes.
     * Uses {@code getAttributes()} on the Form object.
     */
    public List<Map<String, Object>> readAttributes(Object formObj) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<EObject> attrs = invokeEObjectList(formObj, "getAttributes");
        for (EObject a : attrs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", invokeOrNull(a, "getName"));
            // BUG-04 fix: getValueType() is an mcore TypeDescription — format it
            // properly instead of emitting a raw TypeDescriptionImpl@hex toString().
            Object type = invokeOrNull(a, "getValueType");
            if (type != null) {
                m.put("type", McoreTypeReader.format(type));
            }
            out.add(m);
        }
        return out;
    }

    /**
     * Read form commands.
     * Spike §9.1: accessor is {@code getFormCommands()} (not {@code getCommands()}).
     */
    public List<Map<String, Object>> readCommands(Object formObj) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<EObject> cmds = invokeEObjectList(formObj, "getFormCommands");
        for (EObject c : cmds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", invokeOrNull(c, "getName"));
            Object title = invokeOrNull(c, "getTitle");
            if (title != null) {
                String titleStr = extractLocalizedString(title);
                if (titleStr != null) {
                    m.put("title", titleStr);
                }
            }
            out.add(m);
        }
        return out;
    }

    /**
     * Extract a localized string from a localized-string container.
     * Tries "ru" first, then the first available value.
     *
     * <p>BUG-04 fix: EDT localized strings are {@code EMap<String,String>}, which
     * does <b>not</b> implement {@code java.util.Map} — the old code therefore
     * always fell through to {@code toString()} and emitted EMF impl garbage as
     * the command title. We now also accept an EMF {@code EMap} via its
     * {@code map()} view, and return {@code null} (field omitted) instead of
     * garbage when nothing usable is found.
     */
    public static String extractLocalizedString(Object loc) {
        if (loc == null) {
            return null;
        }
        java.util.Map<?, ?> map = null;
        if (loc instanceof java.util.Map<?, ?> m) {
            map = m;
        } else {
            // EMF EMap — obtain its java.util.Map view reflectively.
            try {
                Object view = loc.getClass().getMethod("map").invoke(loc);
                if (view instanceof java.util.Map<?, ?> m) {
                    map = m;
                }
            } catch (ReflectiveOperationException ignored) {
                // not an EMap — fall through
            }
        }
        if (map == null) {
            return null;
        }
        Object ru = map.get("ru");
        if (ru instanceof String s && !s.isEmpty()) {
            return s;
        }
        for (Object v : map.values()) {
            if (v instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    // --- reflection helpers ---

    private static Object invokeOrNull(Object obj, String method) {
        if (obj == null) {
            return null;
        }
        try {
            java.lang.reflect.Method m = findMethod(obj.getClass(), method);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EObject> invokeItems(Object obj) {
        return invokeEObjectList(obj, "getItems");
    }

    @SuppressWarnings("unchecked")
    private static List<EObject> invokeEObjectList(Object obj, String method) {
        if (obj == null) {
            return List.of();
        }
        try {
            java.lang.reflect.Method m = findMethod(obj.getClass(), method);
            if (m == null) {
                return List.of();
            }
            m.setAccessible(true);
            Object v = m.invoke(obj);
            if (v instanceof List) {
                return (List<EObject>) v;
            }
            if (v instanceof Iterable) {
                List<EObject> result = new ArrayList<>();
                for (Object item : (Iterable<?>) v) {
                    if (item instanceof EObject) {
                        result.add((EObject) item);
                    }
                }
                return result;
            }
        } catch (ReflectiveOperationException e) {
            // method not found or inaccessible
        }
        return List.of();
    }

    /**
     * Walk class hierarchy to find a declared method with no parameters.
     * Falls back from getDeclaredMethods to superclasses/interfaces.
     */
    private static java.lang.reflect.Method findMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name);
                return m;
            } catch (NoSuchMethodException e) {
                // try interfaces
                for (Class<?> iface : c.getInterfaces()) {
                    java.lang.reflect.Method m = findMethod(iface, name);
                    if (m != null) {
                        return m;
                    }
                }
            }
        }
        return null;
    }
}
