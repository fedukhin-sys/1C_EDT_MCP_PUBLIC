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
    public Object redact(String toolName, String infobaseKey, Object result) {
        // per-infobase флаг: если база явно объявлена без реальных ПДн — не трогаем
        if (infobaseKey != null && !flags.containsRealPersonalData(infobaseKey)) {
            return result;
        }
        PiiCatalog cat = catalogSupplier.get();
        Tally tally = new Tally();
        Object out = switch (toolName) {
            case "get_variables" -> redactVarList(result, cat, tally);
            case "evaluate" -> redactEvaluate(result, cat, tally);
            case "query_event_log" -> redactEventLog(result, cat, tally);
            default -> deepRegex(result, tally); // get_stack и любые прочие — только regex-сеть
        };
        if (tally.count() > 0) {
            audit.record(toolName, infobaseKey == null ? "-" : infobaseKey,
                         tally.maxSensitivity(), toolName, tally.count());
        }
        return out;
    }

    // ---- get_variables: List<{name,type,value}> ----
    @SuppressWarnings("unchecked")
    private Object redactVarList(Object result, PiiCatalog cat, Tally c) {
        if (!(result instanceof List<?> list)) return deepRegex(result, c);
        for (Object o : list) {
            if (o instanceof Map) redactValueField((Map<String, Object>) o, cat, c);
        }
        return result;
    }

    // ---- evaluate: {ok,value,type} ----
    @SuppressWarnings("unchecked")
    private Object redactEvaluate(Object result, PiiCatalog cat, Tally c) {
        if (result instanceof Map<?, ?> m) {
            if (Boolean.TRUE.equals(m.get("ok"))) {
                redactValueField((Map<String, Object>) m, cat, c);
            } else {
                maskStringField((Map<String, Object>) m, "error", c); // fail-closed: regex по сообщению ошибки
            }
        }
        return result;
    }

    /**
     * Счётчик фактов обезличивания вместе с самой строгой встреченной категорией.
     *
     * <p>Раньше в журнал всегда писалось {@code PERSONAL}, из-за чего запись о сокрытии
     * спец-категории или биометрии выглядела как обычная псевдонимизация ФИО — журнал
     * обезличивания врал о том, что именно скрывалось.
     */
    private static final class Tally {
        private int count;
        private Sensitivity max = Sensitivity.NONE;

        /** @param s категория; {@code NONE} — сработала только content-regex-сеть */
        void hit(Sensitivity s) {
            count++;
            // ordinal растёт от NONE к SPECIAL — см. javadoc Sensitivity.
            if (s.ordinal() > max.ordinal()) max = s;
        }

        int count() { return count; }

        /** Самая строгая категория; если сработал только regex — считаем ПДн (fail-closed). */
        Sensitivity maxSensitivity() {
            return max == Sensitivity.NONE ? Sensitivity.PERSONAL : max;
        }
    }

    /** Обезличивает поле "value" по соседнему "type"/"name". */
    private void redactValueField(Map<String, Object> m, PiiCatalog cat, Tally c) {
        Object valObj = m.get("value");
        if (!(valObj instanceof String value) || value.isEmpty()) return;
        String type = m.get("type") instanceof String t ? t : null;
        String name = m.get("name") instanceof String n ? n : null;

        Sensitivity s = classify(type, name, cat);
        if (s.isSensitive()) {
            m.put("value", pseudo.token(s, value));
            c.hit(s);
        } else {
            String masked = ContentPatterns.maskInline(value);
            if (!masked.equals(value)) { m.put("value", masked); c.hit(Sensitivity.NONE); }
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
    private Object redactEventLog(Object result, PiiCatalog cat, Tally c) {
        if (!(result instanceof Map<?, ?> map)) return deepRegex(result, c);
        Object events = ((Map<String, Object>) map).get("events");
        if (events instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> ev = (Map<String, Object>) o;
                // user — всегда физлицо
                if (ev.get("user") instanceof String u && !u.isEmpty()) {
                    ev.put("user", pseudo.token(Sensitivity.PERSONAL, u)); c.hit(Sensitivity.PERSONAL);
                }
                // dataPresentation / data.value — по объекту metadata
                Sensitivity meta = ev.get("metadata") instanceof String md
                        ? cat.forObject(md) : Sensitivity.NONE;
                if (meta.isSensitive()) {
                    if (ev.get("dataPresentation") instanceof String dp && !dp.isEmpty()) {
                        ev.put("dataPresentation", pseudo.token(meta, dp)); c.hit(meta);
                    }
                    if (ev.get("data") instanceof Map dm && dm.get("value") instanceof String dv) {
                        dm.put("value", pseudo.token(meta, dv)); c.hit(meta);
                    }
                }
                // comment / computer / event / server и прочие строки — regex-сеть
                maskStringField(ev, "comment", c);
                maskStringField(ev, "computer", c);
                maskStringField(ev, "event", c);
                maskStringField(ev, "server", c);
                if (!meta.isSensitive()) {
                    maskStringField(ev, "dataPresentation", c);
                    // объекта нет в каталоге, но в data.value лежат представления ссылок,
                    // ФИО и телефоны из ЖР — fail-closed прогоняем regex-сеть
                    if (ev.get("data") instanceof Map<?, ?> dm) {
                        maskStringField((Map<String, Object>) dm, "value", c);
                    }
                }
            }
        }
        return result;
    }

    private void maskStringField(Map<String, Object> m, String key, Tally c) {
        if (m.get(key) instanceof String s && !s.isEmpty()) {
            String masked = ContentPatterns.maskInline(s);
            if (!masked.equals(s)) { m.put(key, masked); c.hit(Sensitivity.NONE); }
        }
    }

    // ---- fail-closed рекурсивная regex-сеть для любых форм ----
    @SuppressWarnings("unchecked")
    private Object deepRegex(Object node, Tally c) {
        if (node instanceof String s) {
            String masked = ContentPatterns.maskInline(s);
            if (!masked.equals(s)) c.hit(Sensitivity.NONE);
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
}
