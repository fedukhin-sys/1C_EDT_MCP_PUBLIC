package ru.fedukhin.edt.mcp.tools.debug;

import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import org.eclipse.debug.core.model.IBreakpoint;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugArgs;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugEventSource;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugLaunchResult;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugLauncher;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSession;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateReader;
import ru.fedukhin.edt.mcp.tools.debug.internal.ExceptionBreakpointService;

/** {@code debug_client} — launch a 1С thin or thick client under the debugger. */
public class DebugClientTool implements IMcpTool {

    private final DebugLauncher launcher;
    private final DebugSessionRegistry registry;
    private final DebugEventSource events;
    private final ExceptionBreakpointService exceptionBreakpoints;

    /**
     * Производственный конструктор: Guice инжектирует все четыре зависимости.
     * {@code DebugEventSource} связывается с {@code DebugPluginEventSource} в
     * {@code ToolsDebugModule}.
     */
    @Inject
    public DebugClientTool(DebugLauncher launcher, DebugSessionRegistry registry,
                           DebugEventSource events,
                           ExceptionBreakpointService exceptionBreakpoints) {
        this.launcher = launcher;
        this.registry = registry;
        this.events = events;
        this.exceptionBreakpoints = exceptionBreakpoints;
    }

    /**
     * Тестовый конструктор — использует {@link NoopEventSource} и {@code null}
     * {@link ExceptionBreakpointService}, чтобы не обращаться к {@code DebugPlugin.getDefault()}
     * (который {@code null} вне Eclipse-рантайма).
     */
    public DebugClientTool(DebugLauncher launcher, DebugSessionRegistry registry) {
        this(launcher, registry, new NoopEventSource(), null);
    }

    @Override public String name() { return "debug_client"; }

    @Override public String description() {
        return "Launch a 1С thin or thick client under the debugger and attach a debug session";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> clientType = new LinkedHashMap<>();
        clientType.put("type", "string"); clientType.put("enum", List.of("thin", "thick"));
        Map<String, Object> bool = new LinkedHashMap<>(); bool.put("type", "boolean");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("infobase", new LinkedHashMap<>(str));
        properties.put("clientType", clientType);
        properties.put("user", new LinkedHashMap<>(str));
        properties.put("password", new LinkedHashMap<>(str));
        properties.put("stopOnError", bool);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("infobase"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        String infobase = DebugArgs.stringArg(args, "infobase");
        String clientType = DebugArgs.optStringArg(args, "clientType");
        if (clientType == null || clientType.isEmpty()) {
            clientType = "thin";
        }
        String user = DebugArgs.optStringArg(args, "user");
        String password = DebugArgs.optStringArg(args, "password");
        // stopOnError default = true: catch-all exception breakpoint installs before the
        // client connects so the debug-server suspends on any BSL runtime exception,
        // not only on line breakpoints. Opt-out by passing false explicitly.
        Object soeArg = (args == null) ? null : args.get("stopOnError");
        boolean stopOnError = !(soeArg instanceof Boolean) || ((Boolean) soeArg);

        // Install the catch-all exception breakpoint BEFORE launching the client so the
        // debug-server picks it up at attach time. If install fails we still launch — the
        // user can drop a regular line breakpoint or rerun without stopOnError.
        IBreakpoint installedBp = null;
        if (stopOnError && exceptionBreakpoints != null) {
            try {
                installedBp = exceptionBreakpoints.installCatchAll();
            } catch (ToolException e) {
                installedBp = null;
            }
        }

        DebugLaunchResult result;
        try {
            result = launcher.launch(infobase, clientType, user, password);
        } catch (ToolException | RuntimeException e) {
            // Breakpoint глобальный (живёт в workspace breakpoint manager), а снимает его
            // DebugSession.terminate() — но при провале запуска сессия не создаётся вовсе.
            // Без этой уборки catch-all остаётся висеть и ловит исключения в чужих,
            // не-MCP сеансах отладки.
            if (installedBp != null) {
                exceptionBreakpoints.remove(installedBp);
            }
            throw e;
        }

        UUID debugSessionId = UUID.randomUUID();
        UUID clientSessionId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        DebugSession session = new DebugSession(debugSessionId, clientSessionId, result.target(),
                result.launch(), result.clientProcess(), events, new DebugStateReader(),
                result.infobaseName(), result.clientType(), startedAt);
        session.setExceptionBreakpoint(installedBp, exceptionBreakpoints);
        registry.register(session);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("debugSessionId", debugSessionId.toString());
        out.put("clientSessionId", clientSessionId.toString());
        out.put("pid", result.clientProcess().pid());
        out.put("clientType", result.clientType());
        out.put("infobase", result.infobaseName());
        out.put("startedAt", startedAt.toString());
        out.put("stopOnError", installedBp != null);
        return out;
    }

    /** Заглушка для unit-тестов: не обращается к {@code DebugPlugin.getDefault()}. */
    private static final class NoopEventSource implements DebugEventSource {
        @Override public void addListener(org.eclipse.debug.core.IDebugEventSetListener l) {}
        @Override public void removeListener(org.eclipse.debug.core.IDebugEventSetListener l) {}
    }
}
