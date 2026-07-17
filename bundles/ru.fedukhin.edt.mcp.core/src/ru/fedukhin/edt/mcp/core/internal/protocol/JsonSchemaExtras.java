package ru.fedukhin.edt.mcp.core.internal.protocol;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Обход ограничения MCP SDK (mcp-core 1.1.2): {@code McpSchema.JsonSchema} — record с
 * фиксированным набором полей, ключи вроде {@code anyOf}/{@code oneOf} он не несёт, и
 * инструментам с «обязателен один из нескольких аргументов» (query_event_log,
 * get_event_log_path) нечем объявить это клиенту.
 *
 * <p>Сериализацией tools/list управляет наш собственный {@link ObjectMapper}
 * (см. {@code McpServerLifecycle.buildTransport}), поэтому недостающие ключи можно
 * дописать на выходе: {@code ToolSpecAdapter} складывает их сюда под identity самого
 * record-инстанса, а {@link Serializer} при записи JSON добавляет их к полям record.
 *
 * <p>Реестр статический и живёт до {@link #clear()}; {@code buildTransport} чистит его
 * перед каждой пересборкой набора инструментов, чтобы инстансы прошлых серверов не
 * копились.
 */
public final class JsonSchemaExtras {

    /** Ключи, которые несёт сам record — всё остальное из Map-схемы уходит в extras. */
    static final Set<String> RECORD_FIELDS = Set.of(
        "type", "properties", "required", "additionalProperties", "$defs", "definitions");

    private static final Map<McpSchema.JsonSchema, Map<String, Object>> EXTRAS =
        Collections.synchronizedMap(new IdentityHashMap<>());

    private JsonSchemaExtras() { }

    static void register(McpSchema.JsonSchema schema, Map<String, Object> extras) {
        if (extras != null && !extras.isEmpty()) {
            EXTRAS.put(schema, extras);
        }
    }

    public static void clear() {
        EXTRAS.clear();
    }

    /** ObjectMapper, который сериализует {@code JsonSchema} вместе с extras-ключами. */
    public static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule("edt-mcp-json-schema-extras");
        module.addSerializer(McpSchema.JsonSchema.class, new Serializer());
        mapper.registerModule(module);
        return mapper;
    }

    /**
     * Повторяет штатную Jackson-сериализацию record (null-поля опускаются, {@code defs}
     * пишется как {@code $defs}) и дописывает extras-ключи, если схема зарегистрирована.
     */
    static final class Serializer extends JsonSerializer<McpSchema.JsonSchema> {
        @Override
        public void serialize(McpSchema.JsonSchema value, JsonGenerator gen,
                              SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            if (value.type() != null) gen.writeObjectField("type", value.type());
            if (value.properties() != null) gen.writeObjectField("properties", value.properties());
            if (value.required() != null) gen.writeObjectField("required", value.required());
            if (value.additionalProperties() != null) {
                gen.writeObjectField("additionalProperties", value.additionalProperties());
            }
            if (value.defs() != null) gen.writeObjectField("$defs", value.defs());
            if (value.definitions() != null) gen.writeObjectField("definitions", value.definitions());
            Map<String, Object> extras = EXTRAS.get(value);
            if (extras != null) {
                for (Map.Entry<String, Object> e : extras.entrySet()) {
                    gen.writeObjectField(e.getKey(), e.getValue());
                }
            }
            gen.writeEndObject();
        }
    }
}
