package ru.fedukhin.edt.mcp.tools.client;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry.ClientSession;

public class ListRunningClientsTool implements IMcpTool {

    private final ClientProcessRegistry registry;

    @Inject
    public ListRunningClientsTool(ClientProcessRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "list_running_clients"; }
    @Override public String description() { return "List running 1С client sessions (with optional infobase / clientType filter)"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> infobase = new LinkedHashMap<>(); infobase.put("type", "string");
        Map<String, Object> clientType = new LinkedHashMap<>();
        clientType.put("type", "string"); clientType.put("enum", List.of("thin", "thick"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("infobase", infobase);
        properties.put("clientType", clientType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public List<Map<String, Object>> call(Map<String, Object> args) throws ToolException {
        String infobaseFilter  = (args == null) ? null : (String) args.get("infobase");
        String clientTypeFilter = (args == null) ? null : (String) args.get("clientType");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClientSession s : registry.list()) {
            if (infobaseFilter != null && !infobaseFilter.equals(s.infobaseName())) continue;
            if (clientTypeFilter != null && !clientTypeFilter.equals(s.clientType())) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sessionId", s.sessionId().toString());
            entry.put("pid", s.pid());
            entry.put("clientType", s.clientType());
            entry.put("infobase", s.infobaseName());
            entry.put("alive", s.alive());
            entry.put("startedAt", s.startedAt().toString());
            out.add(entry);
        }
        return out;
    }
}
