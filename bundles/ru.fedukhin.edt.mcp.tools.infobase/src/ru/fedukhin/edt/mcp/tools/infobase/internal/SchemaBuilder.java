package ru.fedukhin.edt.mcp.tools.infobase.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Сборка JSON Schema объекта аргументов инструмента: без неё схемы с десятком полей нечитаемы. */
public final class SchemaBuilder {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private SchemaBuilder() {}

    public static SchemaBuilder object() {
        return new SchemaBuilder();
    }

    public SchemaBuilder string(String name, String description, boolean isRequired) {
        return property(name, prop("string", description), isRequired);
    }

    public SchemaBuilder stringEnum(String name, String description, List<String> values, boolean isRequired) {
        Map<String, Object> p = prop("string", description);
        p.put("enum", List.copyOf(values));
        return property(name, p, isRequired);
    }

    public SchemaBuilder bool(String name, String description) {
        return property(name, prop("boolean", description), false);
    }

    public SchemaBuilder integer(String name, String description, int min, int max) {
        Map<String, Object> p = prop("integer", description);
        p.put("minimum", min);
        p.put("maximum", max);
        return property(name, p, false);
    }

    public SchemaBuilder array(String name, String description, Map<String, Object> items, boolean isRequired) {
        Map<String, Object> p = prop("array", description);
        p.put("items", items);
        p.put("minItems", 1);
        return property(name, p, isRequired);
    }

    public Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    private SchemaBuilder property(String name, Map<String, Object> schema, boolean isRequired) {
        properties.put(name, schema);
        if (isRequired) required.add(name);
        return this;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }
}
