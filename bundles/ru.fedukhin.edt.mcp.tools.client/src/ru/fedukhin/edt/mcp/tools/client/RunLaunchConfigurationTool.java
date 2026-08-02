package ru.fedukhin.edt.mcp.tools.client;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IProcess;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.internal.LaunchConfigService;

/**
 * Запуск launch-конфигурации EDT (клиент 1С или отладчик) — тем же кодом, что кнопка
 * запуска в IDE: {@code ILaunchConfiguration.launch(mode)} → {@code RuntimeClientLaunchDelegate}.
 * Учётные данные, тип клиента и версия платформы берутся из самой конфигурации.
 */
public class RunLaunchConfigurationTool implements IMcpTool {

    /**
     * Сколько ждём после старта, прежде чем признать клиента запустившимся: 1cv8 при отказе
     * (неверные учётные данные, битая ИБ, нет прав) умирает за доли секунды с exit-кодом и без
     * stderr — без паузы инструмент рапортовал бы success на мёртвом процессе (паттерн run_client).
     */
    private static final long PROBE_MS = 1500L;

    static final int DEFAULT_TIMEOUT_SECONDS = 300;
    static final int MIN_TIMEOUT_SECONDS = 30;
    static final int MAX_TIMEOUT_SECONDS = 3600;

    private final LaunchConfigService service;
    private final long probeMs;

    @Inject
    public RunLaunchConfigurationTool(LaunchConfigService service) {
        this(service, PROBE_MS);
    }

    /** Test seam: probe без полутора секунд ожидания. */
    RunLaunchConfigurationTool(LaunchConfigService service, long probeMs) {
        this.service = service;
        this.probeMs = probeMs;
    }

    @Override public String name() { return "run_launch_configuration"; }

    @Override public String description() {
        return "Launch an EDT launch configuration (1С client or debug session) exactly like the"
            + " IDE run/debug button — credentials, client type and platform version are taken"
            + " from the configuration itself. Use list_launch_configurations to see names.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> name = new LinkedHashMap<>();
        name.put("type", "string");
        name.put("description", "exact launch configuration name, e.g. 'Upiter Тонкий клиент'");
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("type", "string");
        mode.put("enum", List.of("run", "debug"));
        mode.put("description", "launch mode, default 'run'");
        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer");
        timeout.put("minimum", MIN_TIMEOUT_SECONDS);
        timeout.put("maximum", MAX_TIMEOUT_SECONDS);
        timeout.put("description", "seconds to wait for the launch to complete, default "
            + DEFAULT_TIMEOUT_SECONDS + " (infobase update before start can take minutes)");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);
        properties.put("mode", mode);
        properties.put("timeoutSeconds", timeout);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        String name = RunClientTool.stringArg(args, "name");
        String mode = (args == null) ? null : (String) args.get("mode");
        if (mode == null || mode.isEmpty()) {
            mode = "run";
        }
        if (!"run".equals(mode) && !"debug".equals(mode)) {
            throw new ToolException("mode must be 'run' or 'debug', got '" + mode + "'");
        }
        int timeoutSeconds = intArg(args, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new ToolException("timeoutSeconds must be in [" + MIN_TIMEOUT_SECONDS + ", "
                + MAX_TIMEOUT_SECONDS + "], got " + timeoutSeconds);
        }

        ILaunchConfiguration configuration = service.findByName(name);
        Optional<ILaunch> launched = service.launchWithTimeout(configuration, mode, timeoutSeconds);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("mode", mode);
        if (launched.isEmpty()) {
            out.put("completed", false);
            out.put("note", "launch() did not return within " + timeoutSeconds + " s; it keeps"
                + " running in background (EDT may be updating the infobase before start)."
                + " Re-check later via list_running_clients or the client window.");
            return out;
        }

        out.put("completed", true);
        ILaunch launch = launched.get();
        probeSleep();

        List<Map<String, Object>> processes = new ArrayList<>();
        boolean anyDead = false;
        int deadExit = 0;
        for (IProcess p : launch.getProcesses()) {
            Map<String, Object> proc = new LinkedHashMap<>();
            proc.put("label", p.getLabel());
            boolean terminated = p.isTerminated();
            proc.put("terminated", terminated);
            if (terminated) {
                anyDead = true;
                try {
                    deadExit = p.getExitValue();
                    proc.put("exitCode", deadExit);
                } catch (DebugException e) {
                    proc.put("exitCode", null);
                }
            }
            processes.add(proc);
        }
        out.put("processes", processes);
        out.put("debugTargets", launch.getDebugTargets().length);
        if (anyDead) {
            out.put("warning", "a launched process terminated with exit code " + deadExit
                + " within " + probeMs + " ms — the 1С client did NOT start. 1cv8 reports"
                + " startup failures (authentication refused, infobase locked or damaged,"
                + " missing rights) via exit code only, without stderr.");
        }
        return out;
    }

    private void probeSleep() {
        try {
            Thread.sleep(probeMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) throws ToolException {
        Object v = (args == null) ? null : args.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        throw new ToolException("'" + key + "' must be an integer");
    }
}
