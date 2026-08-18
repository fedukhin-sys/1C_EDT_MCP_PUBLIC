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
import ru.fedukhin.edt.mcp.tools.client.internal.ForeignProcessScanner;

public class ListRunningClientsTool implements IMcpTool {

    private final ClientProcessRegistry registry;

    @Inject
    public ListRunningClientsTool(ClientProcessRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "list_running_clients"; }
    @Override public String description() {
        return "List running 1С client sessions (with optional infobase / clientType filter). "
             + "Pass includeForeign=true to also list platform processes (1cv8/dbgs) started by "
             + "other 1C:EDT instances or left over from previous runs — they are flagged "
             + "foreign=true, they hold the infobase (turning deploy_project into a silent no-op), "
             + "and stop_client cannot terminate them.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> infobase = new LinkedHashMap<>(); infobase.put("type", "string");
        Map<String, Object> clientType = new LinkedHashMap<>();
        clientType.put("type", "string"); clientType.put("enum", List.of("thin", "thick"));
        Map<String, Object> includeForeign = new LinkedHashMap<>();
        includeForeign.put("type", "boolean");
        includeForeign.put("description", "Include platform processes of other EDT instances (default false)");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("infobase", infobase);
        properties.put("clientType", clientType);
        properties.put("includeForeign", includeForeign);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public List<Map<String, Object>> call(Map<String, Object> args) throws ToolException {
        String infobaseFilter  = (args == null) ? null : (String) args.get("infobase");
        String clientTypeFilter = (args == null) ? null : (String) args.get("clientType");
        // По умолчанию выключено: инструмент существовал раньше и отдавал только
        // собственные сеансы. Молча начать возвращать процессы всей машины — значит
        // сломать всех, кто уже разбирает его ответ.
        Object incl = (args == null) ? null : args.get("includeForeign");
        boolean includeForeign = (incl instanceof Boolean b) && b;

        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<Long> ownPids = new java.util.HashSet<>();
        for (ClientSession s : registry.list()) {
            ownPids.add(s.pid());
            if (infobaseFilter != null && !infobaseFilter.equals(s.infobaseName())) continue;
            if (clientTypeFilter != null && !clientTypeFilter.equals(s.clientType())) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sessionId", s.sessionId().toString());
            entry.put("pid", s.pid());
            entry.put("clientType", s.clientType());
            entry.put("infobase", s.infobaseName());
            entry.put("alive", s.alive());
            entry.put("startedAt", s.startedAt().toString());
            entry.put("foreign", false);
            out.add(entry);
        }
        // Чужие процессы кладём в тот же список с признаком foreign, а не отдельной
        // секцией: смена формы ответа сломала бы существующих потребителей.
        // Фильтр clientType к ним неприменим — их тип из командной строки не выводится.
        if (includeForeign) {
            for (Map<String, Object> e : ForeignProcessScanner.scan(ownPids)) {
                if (clientTypeFilter != null) continue;
                if (infobaseFilter != null && !infobaseFilter.equals(e.get("infobase"))) continue;
                out.add(e);
            }
        }
        return out;
    }
}
