package ru.fedukhin.edt.mcp.core.internal.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Inject;
import ru.fedukhin.edt.mcp.core.api.IToolRegistry;

public class McpServerLifecycle {

    /**
     * SSE keep-alive interval. The SDK schedules keep-alive pings only when this
     * is non-null; without them the long-lived SSE socket is silent between
     * requests and Jetty closes it on idle (default 30s), so the client's next
     * fetch hits a dead connection ("fetch failed"). Kept well under
     * {@code McpHttpService}'s connector idle timeout.
     */
    private static final java.time.Duration SSE_KEEP_ALIVE = java.time.Duration.ofSeconds(20);

    private final IToolRegistry tools;
    private final ToolSpecAdapter adapter;

    private HttpServletSseServerTransportProvider transport;
    private McpSyncServer server;

    @Inject
    public McpServerLifecycle(IToolRegistry tools, ToolSpecAdapter adapter) {
        this.tools = tools;
        this.adapter = adapter;
    }

    /**
     * Builds (or rebuilds, after close()) the SDK transport + server. Each call
     * to {@code McpHttpService.start()} routes through here so a fresh transport
     * exists after a stop/start cycle.
     */
    public synchronized HttpServletSseServerTransportProvider buildTransport() {
        if (transport != null) return transport;
        // Pass jsonMapper explicitly — the SDK default goes through ServiceLoader
        // (META-INF/services) and OSGi DS for the mcp-json-jackson2 bundle, which
        // isn't reliably activated in the headless test runtime.
        ObjectMapper objectMapper = new ObjectMapper();
        McpJsonMapper jsonMapper = new JacksonJsonMapper(objectMapper);
        JsonSchemaValidator validator = new NoopJsonSchemaValidator(objectMapper);
        transport = HttpServletSseServerTransportProvider.builder()
            .jsonMapper(jsonMapper)
            .messageEndpoint("/mcp/messages")
            .sseEndpoint("/sse")
            .keepAliveInterval(SSE_KEEP_ALIVE)
            .build();
        server = McpServer.sync(transport)
            .jsonMapper(jsonMapper)
            .jsonSchemaValidator(validator)
            .serverInfo("EDT_MCP", "1.9.0")
            .instructions(SERVER_INSTRUCTIONS)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .tools(tools.tools().stream().map(adapter::adapt).toList())
            .build();
        return transport;
    }

    /**
     * MCP {@code initialize} response carries this as {@code instructions} —
     * a free-text policy hint to the client. Survives bundle reinstall (lives
     * in source), unlike CLAUDE.md/memory which is per-project. Keep it tight:
     * clients re-fetch on every session start.
     */
    private static final String SERVER_INSTRUCTIONS = """
        EDT_MCP serves 1C:EDT IDE state and lets the client mutate projects,
        BSL modules, MdObjects, forms, infobases, and run xUnitFor1C tests.

        Mandatory policy: BEFORE every `deploy_project` call, run
        `check_list_markers` (or `check_run`) on the same project and triage
        the result. The 1cv8 ENTERPRISE start silently exits with code 1
        (no stderr) when a BSL compile error is present, so any blocker
        MUST be fixed before deploy.

        Triage rules (what to fix vs skip):

        BLOCKER — fix before deploy:
        - `checkId = BslEditor` reporting BSL syntax/compile errors
          («Ожидается имя переменной», «Ожидается выражение», «Встроенная
          функция может быть использована только в выражении», «Не разрешена
          слева от ключевого слова препроцессора», «Данный модуль может
          содержать только процедуры и функции» — last two are cascade
          from real compile errors above);
        - any marker with `summary.blocker` or `summary.critical`,
          regardless of `checkId`.

        WARN — skip for current deploy, address separately:
        - SSL style: missing «Возвращаемое значение» block on export funcs;
        - security guideline: missing «безопасный режим перед Выполнить/Вычислить»;
        - deprecation: «Метод устарел» — still functional;
        - Web/Server type-mismatch markers on non-Web modules
          («Тип X не определён [Web-клиент]»);
        - `checkId = MdValidationChecker` on CommonModule flags
          («Клиент (обычное приложение)», «Внешнее соединение»);
        - `MdValidationChecker` orphan UUID markers on deleted objects;
        - any `summary.minor` or `summary.trivial`.

        Severity mapping (observed):
        - marker `severity=error`   → `summary.major` or higher;
        - marker `severity=warning` → `summary.minor`;
        - marker `severity=info`    → `summary.trivial`.

        After fixing blockers, re-run `check_list_markers`. Warnings may
        remain — they don't block 1cv8 ENTERPRISE startup.
        """;

    public synchronized void close() {
        // closeGracefully() returns a Mono — must .block() so the SDK stops
        // serving before Jetty calls server.stop() / join(). Without this
        // Jetty's join() can hang on live SSE connections. A short timeout
        // keeps stuck clients from blocking shutdown forever.
        if (transport != null) {
            try {
                transport.closeGracefully().block(java.time.Duration.ofSeconds(2));
            } catch (Exception ignore) {}
        }
        transport = null;
        server = null;
    }
}
