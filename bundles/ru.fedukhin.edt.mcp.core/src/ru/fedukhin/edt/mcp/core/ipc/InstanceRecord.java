package ru.fedukhin.edt.mcp.core.ipc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Самоописание живой инстанции MCP-сервера. Лежит в
 * {@code ~/.edt-mcp/instances/&lt;pid&gt;.json} и позволяет клиенту сопоставить
 * названный пользователем проект с портом нужной инстанции, не перебирая порты.
 *
 * <p>{@code JsonIgnoreProperties} — на случай, когда рядом работает инстанция
 * другой версии плагина и пишет поля, которых мы ещё не знаем.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstanceRecord(long pid, int port, String bindHost, String sseUrl,
                             String workspacePath, String workspaceName,
                             List<String> projects, String version, String startedAt) {}
