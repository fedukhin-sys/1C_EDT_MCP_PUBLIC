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

public class ToolSpecAdapter {

    private static final ILog LOG = Platform.getLog(ToolSpecAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SyncToolSpecification adapt(IMcpTool tool) {
        McpSchema.Tool toolMeta = McpSchema.Tool.builder()
            .name(tool.name())
            .description(tool.description())
            .inputSchema(toJsonSchema(tool.inputSchema()))
            .build();

        return SyncToolSpecification.builder()
            .tool(toolMeta)
            .callHandler((McpSyncServerExchange exch, McpSchema.CallToolRequest req) -> {
                Map<String, Object> args = req.arguments();
                try {
                    Object result = tool.call(args);
                    String json = MAPPER.writeValueAsString(result);
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(json)))
                        .isError(false)
                        .build();
                } catch (ToolException te) {
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(describe(te))))
                        .isError(true)
                        .build();
                } catch (RuntimeException | java.io.IOException re) {
                    LOG.log(Status.error("MCP tool '" + tool.name() + "' threw", re));
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("internal error: " + describe(re))))
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
        return new McpSchema.JsonSchema(type, properties, required, additionalProperties, defs, definitions);
    }
}
