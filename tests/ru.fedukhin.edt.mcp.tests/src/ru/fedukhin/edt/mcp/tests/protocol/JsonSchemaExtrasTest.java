package ru.fedukhin.edt.mcp.tests.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.internal.protocol.JsonSchemaExtras;
import ru.fedukhin.edt.mcp.core.internal.protocol.ToolSpecAdapter;

/**
 * anyOf/oneOf в inputSchema инструментов должны доезжать до клиента: record
 * McpSchema.JsonSchema их не несёт, поэтому ToolSpecAdapter кладёт их в
 * JsonSchemaExtras, а mapper из createMapper() дописывает при сериализации.
 */
public class JsonSchemaExtrasTest {

    @Before public void resetRegistry() {
        JsonSchemaExtras.clear();
    }

    private static IMcpTool toolWithSchema(Map<String, Object> schema) {
        return new IMcpTool() {
            @Override public String name() { return "fixture_tool"; }
            @Override public String description() { return "fixture"; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public Object call(Map<String, Object> args) { return Map.of(); }
        };
    }

    private static String serializedInputSchema(Map<String, Object> schema) throws Exception {
        SyncToolSpecification spec = new ToolSpecAdapter(null).adapt(toolWithSchema(schema));
        ObjectMapper mapper = JsonSchemaExtras.createMapper();
        return mapper.writeValueAsString(spec.tool().inputSchema());
    }

    @Test public void anyOf_isPublishedInSerializedSchema() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("name", Map.of("type", "string"),
                                        "uuid", Map.of("type", "string")));
        schema.put("anyOf", List.of(
            Map.of("required", List.of("name")),
            Map.of("required", List.of("uuid"))));
        schema.put("additionalProperties", false);

        String json = serializedInputSchema(schema);
        assertTrue("anyOf must survive serialization: " + json, json.contains("\"anyOf\""));
        assertTrue(json.contains("\"required\":[\"name\"]"));
        assertTrue(json.contains("\"required\":[\"uuid\"]"));
        assertTrue(json.contains("\"type\":\"object\""));
        assertTrue(json.contains("\"additionalProperties\":false"));
    }

    @Test public void oneOf_isPublishedInSerializedSchema() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("name", Map.of("type", "string")));
        schema.put("oneOf", List.of(Map.of("required", List.of("name"))));

        String json = serializedInputSchema(schema);
        assertTrue("oneOf must survive serialization: " + json, json.contains("\"oneOf\""));
    }

    @Test public void plainSchema_serializesWithoutExtrasAndWithoutNullFields() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("project", Map.of("type", "string")));
        schema.put("required", List.of("project"));
        schema.put("additionalProperties", false);

        String json = serializedInputSchema(schema);
        assertFalse(json.contains("anyOf"));
        assertFalse(json.contains("oneOf"));
        assertFalse("null fields must be omitted: " + json, json.contains("null"));
        assertFalse("defs must not leak into plain schemas: " + json, json.contains("$defs"));
    }

    @Test public void clear_dropsRegisteredExtras() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("anyOf", List.of(Map.of("required", List.of("name"))));

        SyncToolSpecification spec = new ToolSpecAdapter(null).adapt(toolWithSchema(schema));
        JsonSchemaExtras.clear();
        String json = JsonSchemaExtras.createMapper().writeValueAsString(spec.tool().inputSchema());
        assertFalse("cleared registry must not append extras: " + json, json.contains("anyOf"));
    }
}
