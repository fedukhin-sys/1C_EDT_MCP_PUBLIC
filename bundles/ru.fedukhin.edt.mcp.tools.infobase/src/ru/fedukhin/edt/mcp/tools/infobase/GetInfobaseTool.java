package ru.fedukhin.edt.mcp.tools.infobase;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

public class GetInfobaseTool implements IMcpTool {

    private final InfobaseRegistry registry;

    @Inject
    public GetInfobaseTool(InfobaseRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "get_infobase"; }
    @Override public String description() { return "Get infobase details by name or uuid"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> name = new LinkedHashMap<>(); name.put("type", "string");
        Map<String, Object> uuid = new LinkedHashMap<>(); uuid.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);
        properties.put("uuid", uuid);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("oneOf", List.of(
            Map.of("required", List.of("name")),
            Map.of("required", List.of("uuid"))
        ));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String name = args == null ? null : (String) args.get("name");
        String uuidStr = args == null ? null : (String) args.get("uuid");
        if ((name == null || name.isEmpty()) && (uuidStr == null || uuidStr.isEmpty())) {
            throw new ToolException("either 'name' or 'uuid' is required");
        }
        Optional<InfobaseReference> hit;
        if (uuidStr != null && !uuidStr.isEmpty()) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidStr); }
            catch (IllegalArgumentException e) { throw new ToolException("invalid uuid: " + uuidStr); }
            hit = registry.findByUuid(uuid);
        } else {
            hit = registry.findByName(name);
        }
        InfobaseReference ref = hit.orElseThrow(() ->
            new ToolException("infobase '" + (uuidStr != null ? uuidStr : name) + "' not found"));
        return ListInfobasesTool.serialise(ref);
    }
}
