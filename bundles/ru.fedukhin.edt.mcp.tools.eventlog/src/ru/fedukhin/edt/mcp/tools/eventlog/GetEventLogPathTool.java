package ru.fedukhin.edt.mcp.tools.eventlog;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogLocator;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

/**
 * Resolves the {@code 1Cv8Log} directory for an infobase registered in EDT.
 * For FILE infobases the path is derived from the connection string; for SERVER
 * infobases it walks the cluster registry ({@code srvinfo/reg_<port>/1CV8Clst.lst}).
 */
public class GetEventLogPathTool implements IMcpTool {

    private final InfobaseRegistry registry;

    @Inject
    public GetEventLogPathTool(InfobaseRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "get_event_log_path"; }
    @Override public String description() {
        // Требование «ровно один из name/uuid» объявлено в схеме через oneOf, но MCP SDK его
        // не публикует (см. ToolSpecAdapter.toJsonSchema) — поэтому дублируем словами.
        return "Get path to the 1Cv8Log directory and partition list for an infobase. "
             + "Requires exactly one of: name or uuid.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> name = new LinkedHashMap<>(); name.put("type", "string");
        Map<String, Object> uuid = new LinkedHashMap<>(); uuid.put("type", "string");
        Map<String, Object> srvinfo = new LinkedHashMap<>();
        srvinfo.put("type", "string");
        srvinfo.put("description", "Override path to the 1cv8 srvinfo directory. Used for SERVER infobases when auto-discovery (default Program Files paths) fails or the cluster runs in a non-standard location.");
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("type", "integer");
        port.put("description", "Cluster registry port (default 1541). Server log path is <srvinfo>/reg_<port>/.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);
        properties.put("uuid", uuid);
        properties.put("srvinfoDir", srvinfo);
        properties.put("clusterPort", port);

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
        InfobaseReference ref = resolveInfobase(args);

        EventLogLocator locator = new EventLogLocator();
        if (args != null) {
            Object srv = args.get("srvinfoDir");
            if (srv instanceof String s && !s.isBlank()) {
                locator.setSrvinfoDir(Paths.get(s));
            }
            Object port = args.get("clusterPort");
            if (port instanceof Number n) {
                locator.setClusterPort(n.intValue());
            }
        }
        EventLogLocator.ResolvedLog r = locator.locate(ref);

        Path logDir = r.logDir;
        List<Map<String, Object>> partitions = EventLogLocator.listPartitions(logDir);
        long lgfSize = -1;
        Path lgf = logDir.resolve("1Cv8.lgf");
        try {
            if (java.nio.file.Files.isRegularFile(lgf)) lgfSize = java.nio.file.Files.size(lgf);
        } catch (java.io.IOException ignored) { /* report -1 */ }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("infobase", ref.getName());
        out.put("type", r.infobaseType);
        out.put("logDir", logDir.toString());
        out.put("exists", r.exists);
        out.put("format", "lgf");                            // .lgd (SQLite) not yet supported
        out.put("encoding", EventLogLocator.logEncoding());
        out.put("lgfSize", lgfSize);
        out.put("partitions", partitions);
        if (r.clusterRef != null)   out.put("clusterRef", r.clusterRef);
        if (r.clusterIbUuid != null) out.put("clusterIbUuid", r.clusterIbUuid);
        if (r.srvinfoDir != null)   out.put("srvinfoDir", r.srvinfoDir.toString());
        if (r.clusterPort != 0)     out.put("clusterPort", r.clusterPort);
        return out;
    }

    InfobaseReference resolveInfobase(Map<String, Object> args) throws ToolException {
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
        return hit.orElseThrow(() -> new ToolException(
            "infobase '" + (uuidStr != null ? uuidStr : name) + "' not found"));
    }
}
