package ru.fedukhin.edt.mcp.core.privacy;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Централизованный обезличиватель. Structure-aware для известных инструментов,
 * с fail-closed content-regex по остальным строкам.
 */
public final class PrivacyRedactor implements IPrivacyFilter {

    private final Supplier<PiiCatalog> catalogSupplier;
    private final Pseudonymizer pseudo;
    private final InfobaseFlagStore flags;
    private final AuditLog audit;

    public PrivacyRedactor(Supplier<PiiCatalog> catalogSupplier, Pseudonymizer pseudo,
                           InfobaseFlagStore flags, AuditLog audit) {
        this.catalogSupplier = catalogSupplier;
        this.pseudo = pseudo;
        this.flags = flags;
        this.audit = audit;
    }

    @Override
    public Object redact(String toolName, Map<String, Object> args, Object result) {
        String infobaseKey = infobaseKey(args);
        // per-infobase флаг: если база явно объявлена без реальных ПДн — не трогаем
        if (infobaseKey != null && !flags.containsRealPersonalData(infobaseKey)) {
            return result;
        }
        PiiCatalog cat = catalogSupplier.get();
        int[] counter = {0};
        Object out = switch (toolName) {
            case "get_variables" -> redactVarList(result, cat, counter);
            case "evaluate" -> redactEvaluate(result, cat, counter);
            case "query_event_log" -> redactEventLog(result, cat, counter);
            default -> deepRegex(result, counter); // get_stack и любые прочие — только regex-сеть
        };
        if (counter[0] > 0) {
            audit.record(toolName, infobaseKey == null ? "-" : infobaseKey,
                         Sensitivity.PERSONAL, toolName, counter[0]);
        }
        return out;
    }

    // ---- get_variables: List<{name,type,value}> ----
    @SuppressWarnings("unchecked")
    private Object redactVarList(Object result, PiiCatalog cat, int[] c) {
        if (!(result instanceof List<?> list)) return deepRegex(result, c);
        for (Object o : list) {
            if (o instanceof Map) redactValueField((Map<String, Object>) o, cat, c);
        }
        return result;
    }

    // ---- evaluate: {ok,value,type} ----
    @SuppressWarnings("unchecked")
    private Object redactEvaluate(Object result, PiiCatalog cat, int[] c) {
        if (result instanceof Map<?, ?> m) {
            if (Boolean.TRUE.equals(m.get("ok"))) {
                redactValueField((Map<String, Object>) m, cat, c);
            } else {
                maskStringField((Map<String, Object>) m, "error", c); // fail-closed: regex по сообщению ошибки
            }
        }
        return result;
    }

    /** Обезличивает поле "value" по соседнему "type"/"name". */
    private void redactValueField(Map<String, Object> m, PiiCatalog cat, int[] c) {
        Object valObj = m.get("value");
        if (!(valObj instanceof String value) || value.isEmpty()) return;
        String type = m.get("type") instanceof String t ? t : null;
        String name = m.get("name") instanceof String n ? n : null;

        Sensitivity s = classify(type, name, cat);
        if (s.isSensitive()) {
            m.put("value", pseudo.token(s, value));
            c[0]++;
        } else {
            String masked = ContentPatterns.maskInline(value);
            if (!masked.equals(value)) { m.put("value", masked); c[0]++; }
        }
    }

    private Sensitivity classify(String type, String name, PiiCatalog cat) {
        // слой 1: каталог по объекту ссылочного типа
        if (type != null) {
            var full = BslTypeNames.objectFullName(type);
            if (full.isPresent()) {
                Sensitivity s = cat.forObject(full.get());
                if (s.isSensitive()) return s;
            }
        }
        // слой 2: словарь имён
        return AttributeNameDictionary.classify(name);
    }

    // ---- query_event_log: {..., events:[{user,comment,metadata,dataPresentation,data:{type,value}, ...}]} ----
    @SuppressWarnings("unchecked")
    private Object redactEventLog(Object result, PiiCatalog cat, int[] c) {
        if (!(result instanceof Map<?, ?> map)) return deepRegex(result, c);
        Object events = ((Map<String, Object>) map).get("events");
        if (events instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> ev = (Map<String, Object>) o;
                // user — всегда физлицо
                if (ev.get("user") instanceof String u && !u.isEmpty()) {
                    ev.put("user", pseudo.token(Sensitivity.PERSONAL, u)); c[0]++;
                }
                // dataPresentation / data.value — по объекту metadata
                Sensitivity meta = ev.get("metadata") instanceof String md
                        ? cat.forObject(md) : Sensitivity.NONE;
                if (meta.isSensitive()) {
                    if (ev.get("dataPresentation") instanceof String dp && !dp.isEmpty()) {
                        ev.put("dataPresentation", pseudo.token(meta, dp)); c[0]++;
                    }
                    if (ev.get("data") instanceof Map dm && dm.get("value") instanceof String dv) {
                        dm.put("value", pseudo.token(meta, dv)); c[0]++;
                    }
                }
                // comment / computer / прочие строки — regex-сеть
                maskStringField(ev, "comment", c);
                maskStringField(ev, "computer", c);
                if (!meta.isSensitive()) maskStringField(ev, "dataPresentation", c);
            }
        }
        return result;
    }

    private void maskStringField(Map<String, Object> m, String key, int[] c) {
        if (m.get(key) instanceof String s && !s.isEmpty()) {
            String masked = ContentPatterns.maskInline(s);
            if (!masked.equals(s)) { m.put(key, masked); c[0]++; }
        }
    }

    // ---- fail-closed рекурсивная regex-сеть для любых форм ----
    @SuppressWarnings("unchecked")
    private Object deepRegex(Object node, int[] c) {
        if (node instanceof String s) {
            String masked = ContentPatterns.maskInline(s);
            if (!masked.equals(s)) c[0]++;
            return masked;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new java.util.ArrayList<>(list.size());
            for (Object o : list) copy.add(deepRegex(o, c));
            return copy;
        }
        if (node instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) copy.put(e.getKey(), deepRegex(e.getValue(), c));
            return copy;
        }
        return node;
    }

    /** Ключ инфобазы из args (name/uuid). null → неизвестна → fail-closed (обрабатываем). */
    private static String infobaseKey(Map<String, Object> args) {
        if (args == null) return null;
        Object name = args.get("name");
        if (name instanceof String s && !s.isBlank()) return s;
        Object uuid = args.get("uuid");
        if (uuid instanceof String s && !s.isBlank()) return s;
        return null;
    }
}
