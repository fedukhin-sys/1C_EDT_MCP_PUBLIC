package ru.fedukhin.edt.mcp.tools.client;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientLauncher;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry.ClientSession;
import ru.fedukhin.edt.mcp.tools.client.internal.InfobaseLookup;

public class RunClientTool implements IMcpTool {

    private final InfobaseLookup lookup;
    private final ClientLauncher launcher;
    private final ClientProcessRegistry registry;

    @Inject
    public RunClientTool(InfobaseLookup lookup, ClientLauncher launcher, ClientProcessRegistry registry) {
        this.lookup = lookup;
        this.launcher = launcher;
        this.registry = registry;
    }

    @Override public String name() { return "run_client"; }
    @Override public String description() { return "Launch a 1С thin or thick client process against an infobase"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> infobase = new LinkedHashMap<>(); infobase.put("type", "string");
        Map<String, Object> clientType = new LinkedHashMap<>();
        clientType.put("type", "string"); clientType.put("enum", List.of("thin", "thick"));
        Map<String, Object> user = new LinkedHashMap<>(); user.put("type", "string");
        Map<String, Object> password = new LinkedHashMap<>(); password.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("infobase", infobase);
        properties.put("clientType", clientType);
        properties.put("user", user);
        properties.put("password", password);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("infobase"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String infobaseName = stringArg(args, "infobase");
        String clientType = (args == null) ? null : (String) args.get("clientType");
        if (clientType == null || clientType.isEmpty()) clientType = "thin";
        String user = (args == null) ? null : (String) args.get("user");
        String password = (args == null) ? null : (String) args.get("password");

        InfobaseReference ref = lookup.findByName(infobaseName).orElseThrow(() ->
            new ToolException("infobase '" + infobaseName + "' not found"));
        Process p = launcher.launch(ref, clientType, user, password);
        ClientSession s = registry.register(infobaseName, clientType, p);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", s.sessionId().toString());
        out.put("pid", s.pid());
        out.put("clientType", s.clientType());
        out.put("infobase", s.infobaseName());
        out.put("startedAt", s.startedAt().toString());
        return out;
    }

    static String stringArg(Map<String, Object> args, String key) throws ToolException {
        Object v = (args == null) ? null : args.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new ToolException("missing or empty '" + key + "' argument");
        }
        return (String) v;
    }
}
