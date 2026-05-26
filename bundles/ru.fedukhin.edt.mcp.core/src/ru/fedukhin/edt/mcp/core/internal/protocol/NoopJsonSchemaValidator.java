package ru.fedukhin.edt.mcp.core.internal.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.Map;

/**
 * Pass-through JsonSchemaValidator. We trust our own tool definitions and don't
 * need server-side schema enforcement at this stage; this also lets the core
 * bundle hand the SDK a validator without depending on the
 * `com.networknt:json-schema-validator` ServiceLoader path, which isn't
 * activated in the headless test runtime.
 */
final class NoopJsonSchemaValidator implements JsonSchemaValidator {

    private final ObjectMapper mapper;

    NoopJsonSchemaValidator(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public ValidationResponse validate(Map<String, Object> schema, Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            return ValidationResponse.asValid(json);
        } catch (Exception e) {
            return ValidationResponse.asValid("{}");
        }
    }
}
