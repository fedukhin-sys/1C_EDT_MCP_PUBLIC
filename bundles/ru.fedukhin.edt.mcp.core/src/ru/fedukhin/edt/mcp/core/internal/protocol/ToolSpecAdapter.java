package ru.fedukhin.edt.mcp.core.internal.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.privacy.ContentPatterns;
import ru.fedukhin.edt.mcp.core.privacy.IPrivacyFilter;

public class ToolSpecAdapter {

    private static final ILog LOG = Platform.getLog(ToolSpecAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IPrivacyFilter privacyFilter;

    public ToolSpecAdapter(IPrivacyFilter privacyFilter) {
        this.privacyFilter = privacyFilter;
    }

    /** Прогоняет результат через слой обезличивания, если инструмент возвращает данные ИБ. */
    public Object applyPrivacy(IMcpTool tool, Map<String, Object> args, Object result) {
        if (tool.returnsInfobaseData() && privacyFilter != null) {
            return privacyFilter.redact(tool.name(), tool.privacyInfobaseKey(args), result);
        }
        return result;
    }

    /**
     * Маскирует текст ошибки инструмента, возвращающего данные ИБ: BM/EMF/debug-движок
     * вкладывают в message фрагменты значений базы, а ветка catch идёт мимо applyPrivacy.
     * Structure-aware слой тут неприменим (текст свободный) — только content-regex.
     */
    static String maskError(IMcpTool tool, String message) {
        if (!tool.returnsInfobaseData() || message == null) return message;
        return ContentPatterns.maskInline(message);
    }

    public SyncToolSpecification adapt(IMcpTool tool) {
        McpSchema.Tool toolMeta = McpSchema.Tool.builder()
            .name(tool.name())
            .description(tool.description())
            .inputSchema(toJsonSchema(tool.inputSchema()))
            .build();

        return SyncToolSpecification.builder()
            .tool(toolMeta)
            .callHandler((McpSyncServerExchange exch, McpSchema.CallToolRequest req) -> {
                // Клиент вправе не прислать arguments вовсе; инструменты без обязательных
                // аргументов на null падали NPE вместо честной работы.
                Map<String, Object> args = req.arguments() != null ? req.arguments() : Map.of();
                try {
                    Object result = applyPrivacy(tool, args, tool.call(args));
                    String json = MAPPER.writeValueAsString(result);
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(json)))
                        .isError(false)
                        .build();
                } catch (ToolException te) {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(maskError(tool, describe(te)))))
                        .isError(true)
                        .build();
                } catch (Throwable t) {
                    // Ловим Throwable, а не RuntimeException: дрейф API EDT между версиями даёт
                    // NoSuchMethodError/NoClassDefFoundError — это Error, и он рвал бы SSE-сессию
                    // целиком вместо ошибки одного вызова.
                    // Прерывание проглатывать нельзя — восстанавливаем флаг для вызывающего.
                    if (t instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    LOG.log(Status.error("MCP tool '" + tool.name() + "' threw", t));
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(
                            "internal error: " + maskError(tool, describe(t)))))
                        .isError(true)
                        .build();
                }
            })
            .build();
    }

    /**
     * Builds a human-readable message for a thrown {@code Throwable}. {@code getMessage()}
     * is often {@code null} on BM/EMF-wrapped exceptions — in that case walk the cause
     * chain and, as a last resort, fall back to the class name so the MCP client never
     * sees a bare {@code "null"}.
     */
    static String describe(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && !msg.isBlank()) {
                return (cur == t) ? msg : cur.getClass().getSimpleName() + ": " + msg;
            }
        }
        return t.getClass().getName();
    }

    /**
     * MCP SDK (mcp-core 1.1.2): {@code McpSchema.JsonSchema} — record с фиксированным набором
     * полей (type/properties/required/additionalProperties/$defs/definitions); ключи вроде
     * {@code anyOf}/{@code oneOf} он не несёт, а вторая перегрузка {@code Tool.Builder.inputSchema
     * (McpJsonMapper, String)} сводится к тому же record через {@code McpSchema.parseSchema}.
     * Остальные ключи Map-схемы поэтому уходят в {@link JsonSchemaExtras} и дописываются к JSON
     * на сериализации tools/list (наш ObjectMapper, см. {@code McpServerLifecycle}).
     */
    @SuppressWarnings("unchecked")
    private static McpSchema.JsonSchema toJsonSchema(Map<String, Object> schema) {
        if (schema == null) {
            return new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
        }
        String type = (String) schema.getOrDefault("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        Object addProps = schema.get("additionalProperties");
        Boolean additionalProperties = (addProps instanceof Boolean) ? (Boolean) addProps : null;
        Map<String, Object> defs = (Map<String, Object>) schema.get("$defs");
        Map<String, Object> definitions = (Map<String, Object>) schema.get("definitions");
        McpSchema.JsonSchema js =
            new McpSchema.JsonSchema(type, properties, required, additionalProperties, defs, definitions);
        Map<String, Object> extras = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : schema.entrySet()) {
            if (!JsonSchemaExtras.RECORD_FIELDS.contains(e.getKey())) {
                extras.put(e.getKey(), e.getValue());
            }
        }
        JsonSchemaExtras.register(js, extras);
        return js;
    }
}
