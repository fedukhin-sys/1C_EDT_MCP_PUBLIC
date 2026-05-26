package ru.fedukhin.edt.mcp.core.internal.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;

/**
 * Minimal McpJsonMapper backed by a Jackson ObjectMapper. Bypasses the SDK's
 * own JacksonMcpJsonMapper (whose package is not exported in mcp-json-jackson2
 * 1.1.2) so the core bundle can hand the SDK a mapper without depending on the
 * runtime ServiceLoader/OSGi DS that the SDK uses to discover suppliers.
 */
final class JacksonJsonMapper implements McpJsonMapper {

    private final ObjectMapper mapper;

    JacksonJsonMapper(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public <T> T readValue(String src, Class<T> type) throws IOException {
        return mapper.readValue(src, type);
    }
    @Override public <T> T readValue(byte[] src, Class<T> type) throws IOException {
        return mapper.readValue(src, type);
    }
    @Override public <T> T readValue(String src, TypeRef<T> type) throws IOException {
        return mapper.readValue(src, new TypeReference<T>() {
            @Override public java.lang.reflect.Type getType() { return type.getType(); }
        });
    }
    @Override public <T> T readValue(byte[] src, TypeRef<T> type) throws IOException {
        return mapper.readValue(src, new TypeReference<T>() {
            @Override public java.lang.reflect.Type getType() { return type.getType(); }
        });
    }
    @Override public <T> T convertValue(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }
    @Override public <T> T convertValue(Object value, TypeRef<T> type) {
        return mapper.convertValue(value, new TypeReference<T>() {
            @Override public java.lang.reflect.Type getType() { return type.getType(); }
        });
    }
    @Override public String writeValueAsString(Object value) throws IOException {
        return mapper.writeValueAsString(value);
    }
    @Override public byte[] writeValueAsBytes(Object value) throws IOException {
        return mapper.writeValueAsBytes(value);
    }
}
