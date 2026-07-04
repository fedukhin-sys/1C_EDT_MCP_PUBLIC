package ru.fedukhin.edt.mcp.core.api;

import java.util.Map;

/**
 * An MCP tool exposed by the server. Tool implementations live in extension
 * bundles registered via {@code ru.fedukhin.edt.mcp.core.tool} extension point.
 *
 * <p>The API deliberately uses {@code Map<String, Object>} / plain {@code Object}
 * rather than a JSON binding type so extension bundles don't need to depend on
 * Jackson (Jackson is bundled inside core via {@code Bundle-ClassPath} and is
 * not exported). The core's tool adapter does the JSON conversion internally.
 */
public interface IMcpTool {

    String name();

    String description();

    /** JSON Schema of the tool's input, as a JSON-like Map. */
    Map<String, Object> inputSchema();

    /** Invoke the tool. Returns any JSON-serializable value (Map, List, primitives, null). */
    Object call(Map<String, Object> args) throws ToolException;

    /**
     * true, если результат инструмента может содержать реальные данные ИБ (ПДн/контрагенты).
     * Такие результаты проходят централизованный слой обезличивания в ToolSpecAdapter.
     */
    default boolean returnsInfobaseData() { return false; }
}
