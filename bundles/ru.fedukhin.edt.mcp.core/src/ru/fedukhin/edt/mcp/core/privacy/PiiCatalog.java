package ru.fedukhin.edt.mcp.core.privacy;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Immutable каталог чувствительных объектов/реквизитов. Ключ реквизита — "Объект|Реквизит". */
public final class PiiCatalog {

    private final Map<String, Sensitivity> objects;
    private final Map<String, Sensitivity> attributes;

    private PiiCatalog(Map<String, Sensitivity> objects, Map<String, Sensitivity> attributes) {
        this.objects = objects;
        this.attributes = attributes;
    }

    public Sensitivity forObject(String objectFullName) {
        return objects.getOrDefault(objectFullName, Sensitivity.NONE);
    }

    public Sensitivity forAttribute(String objectFullName, String attributeName) {
        return attributes.getOrDefault(objectFullName + "|" + attributeName, Sensitivity.NONE);
    }

    public Map<String, Sensitivity> objects() { return java.util.Collections.unmodifiableMap(objects); }
    public Map<String, Sensitivity> attributes() { return java.util.Collections.unmodifiableMap(attributes); }
    public boolean isEmpty() { return objects.isEmpty() && attributes.isEmpty(); }

    /** Union нескольких каталогов; при конфликте берётся более строгий класс (больший ordinal). */
    public static PiiCatalog merge(Collection<PiiCatalog> parts) {
        Builder b = builder();
        for (PiiCatalog p : parts) {
            p.objects.forEach(b::object);
            p.attributes.forEach((k, v) -> {
                int bar = k.indexOf('|');
                b.attribute(k.substring(0, bar), k.substring(bar + 1), v);
            });
        }
        return b.build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<String, Sensitivity> objects = new HashMap<>();
        private final Map<String, Sensitivity> attributes = new HashMap<>();

        public Builder object(String fullName, Sensitivity s) {
            objects.merge(fullName, s, Builder::stricter);
            return this;
        }
        public Builder attribute(String objectFullName, String attr, Sensitivity s) {
            attributes.merge(objectFullName + "|" + attr, s, Builder::stricter);
            return this;
        }
        private static Sensitivity stricter(Sensitivity a, Sensitivity b) {
            return a.ordinal() >= b.ordinal() ? a : b;
        }
        public PiiCatalog build() { return new PiiCatalog(objects, attributes); }
    }
}
