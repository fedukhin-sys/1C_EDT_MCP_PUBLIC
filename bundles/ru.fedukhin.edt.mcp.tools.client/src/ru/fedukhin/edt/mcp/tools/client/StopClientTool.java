package ru.fedukhin.edt.mcp.tools.client;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry.ClientSession;

public class StopClientTool implements IMcpTool {

    private final ClientProcessRegistry registry;

    @Inject
    public StopClientTool(ClientProcessRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "stop_client"; }
    @Override public String description() { return "Stop a running 1С client session by sessionId (graceful → force-kill on timeout)"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> sessionId = new LinkedHashMap<>(); sessionId.put("type", "string");
        Map<String, Object> force = new LinkedHashMap<>(); force.put("type", "boolean");
        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer"); timeout.put("minimum", 1); timeout.put("maximum", 60);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", sessionId);
        properties.put("force", force);
        properties.put("gracefulTimeoutSeconds", timeout);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("sessionId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String sessionStr = RunClientTool.stringArg(args, "sessionId");
        UUID id;
        try { id = UUID.fromString(sessionStr); }
        catch (IllegalArgumentException e) { throw new ToolException("invalid sessionId: " + sessionStr); }

        Object f = (args == null) ? null : args.get("force");
        boolean force = f instanceof Boolean ? (Boolean) f : false;
        Object t = (args == null) ? null : args.get("gracefulTimeoutSeconds");
        long graceful = t instanceof Number ? ((Number) t).longValue() : 5L;

        ClientSession s = registry.get(id).orElseThrow(() ->
            new ToolException("session '" + sessionStr + "' not found"));
        Process p = s.process();

        Integer exitCode;
        try {
            if (!p.isAlive()) {
                exitCode = p.exitValue();
            } else if (force) {
                p.destroyForcibly();
                exitCode = p.waitFor(2L, TimeUnit.SECONDS) ? p.exitValue() : null;
            } else {
                p.destroy();
                if (!p.waitFor(graceful, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    exitCode = p.waitFor(2L, TimeUnit.SECONDS) ? p.exitValue() : null;
                } else {
                    exitCode = p.exitValue();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("interrupted while stopping session '" + sessionStr + "'");
        }

        if (exitCode == null) {
            // Process still alive after destroyForcibly — keep registry entry for retry.
            throw new ToolException("failed to stop session '" + sessionStr + "': process still alive");
        }

        registry.remove(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stopped", true);
        out.put("exitCode", exitCode);
        return out;
    }
}
