package ru.fedukhin.edt.mcp.core.internal.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Inject;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
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

    /**
     * Кто мы: путь рабочей области и открытые проекты. {@code null} — вне рабочей
     * области (headless-тесты), тогда идентификация просто не публикуется.
     */
    public record WorkspaceIdentity(String path, java.util.List<String> projects) {}

    private final IToolRegistry tools;
    private final ToolSpecAdapter adapter;
    private final java.util.function.Supplier<WorkspaceIdentity> identity;

    private HttpServletSseServerTransportProvider transport;
    private McpSyncServer server;

    @Inject
    public McpServerLifecycle(IToolRegistry tools, ToolSpecAdapter adapter) {
        this(tools, adapter, McpServerLifecycle::currentWorkspace);
    }

    /** Test seam — явная идентификация вместо чтения рабочей области. */
    public McpServerLifecycle(IToolRegistry tools, ToolSpecAdapter adapter,
                              java.util.function.Supplier<WorkspaceIdentity> identity) {
        this.tools = tools;
        this.adapter = adapter;
        this.identity = identity;
    }

    /** Идентификация текущей инстанции; нужна ещё и реестру инстанций в {@code McpHttpService}. */
    public WorkspaceIdentity workspaceIdentity() { return identity.get(); }

    public String serverVersion() { return bundleVersion(); }

    /**
     * Вне рабочей области (юнит-тесты вне OSGi) возвращает {@code null}, а не падает:
     * идентификация — дополнение к протоколу, из-за неё сервер подниматься не перестаёт.
     */
    static WorkspaceIdentity currentWorkspace() {
        try {
            org.eclipse.core.resources.IWorkspaceRoot root =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
            org.eclipse.core.runtime.IPath loc = root.getLocation();
            java.util.List<String> names = new java.util.ArrayList<>();
            for (org.eclipse.core.resources.IProject p : root.getProjects()) {
                if (p.isOpen()) names.add(p.getName());
            }
            return new WorkspaceIdentity(loc == null ? "(unknown)" : loc.toString(), names);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
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
        // JsonSchemaExtras: mapper дописывает anyOf/oneOf к JsonSchema-record в tools/list;
        // реестр чистится перед пересборкой набора инструментов, чтобы не копить инстансы.
        JsonSchemaExtras.clear();
        ObjectMapper objectMapper = JsonSchemaExtras.createMapper();
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
            .serverInfo(new McpSchema.Implementation("EDT_MCP", serverTitle(), bundleVersion()))
            .instructions(buildInstructions(identity.get()))
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .tools(tools.tools().stream().map(adapter::adapt).toList())
            .build();
        return transport;
    }

    /**
     * Заголовок сервера виден пользователю в клиенте. При нескольких инстанциях
     * EDT это единственное, чем они различаются в списке подключений.
     */
    private String serverTitle() {
        WorkspaceIdentity id = identity.get();
        if (id == null || id.path() == null) return "EDT_MCP";
        java.nio.file.Path leaf;
        try {
            leaf = java.nio.file.Path.of(id.path()).getFileName();
        } catch (RuntimeException e) {
            return "EDT_MCP";
        }
        return leaf == null ? "EDT_MCP" : "EDT_MCP @ " + leaf;
    }

    /**
     * Первый абзац называет обслуживаемую рабочую область. Инструкция уходит клиенту
     * в каждом {@code initialize}, поэтому это самый надёжный способ дать сессии понять,
     * что она подключилась не к той инстанции, — он не зависит ни от каких файлов на диске.
     */
    public static String buildInstructions(WorkspaceIdentity id) {
        if (id == null) return POLICY;
        String projects = (id.projects() == null || id.projects().isEmpty())
            ? "(открытых проектов нет)"
            : String.join(", ", id.projects());
        return "Этот сервер обслуживает workspace " + id.path() + ".\n"
             + "Открытые проекты: " + projects + ".\n"
             + "Если пользователь работает с другим проектом — вы подключились не к тому серверу:\n"
             + "вызовите get_workspace_info, сообщите об ошибке и НЕ выполняйте изменяющих операций.\n\n"
             + POLICY;
    }

    /**
     * MCP {@code initialize} response carries this as {@code instructions} —
     * a free-text policy hint to the client. Survives bundle reinstall (lives
     * in source), unlike CLAUDE.md/memory which is per-project. Keep it tight:
     * clients re-fetch on every session start.
     */
    private static final String POLICY = """
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
            } catch (Exception e) {
                Platform.getLog(getClass()).log(Status.warning("MCP transport close failed", e));
            }
        }
        if (server != null) {
            try {
                server.close();
            } catch (Exception e) {
                Platform.getLog(getClass()).log(Status.warning("MCP server close failed", e));
            }
        }
        transport = null;
        server = null;
    }

    /**
     * Версия из манифеста бандла: раньше здесь был литерал, который отставал от релиза и врал
     * клиенту в ответе {@code initialize} (застрял на 1.9.0). Вне OSGi (юнит-тесты) бандла нет —
     * тогда версия неизвестна.
     */
    private String bundleVersion() {
        Bundle bundle = FrameworkUtil.getBundle(getClass());
        return bundle != null ? bundle.getVersion().toString() : "0.0.0.unknown";
    }
}
