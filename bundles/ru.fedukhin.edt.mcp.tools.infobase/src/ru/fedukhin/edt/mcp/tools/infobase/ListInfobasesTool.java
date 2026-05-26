package ru.fedukhin.edt.mcp.tools.infobase;

import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.InfobaseType;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

public class ListInfobasesTool implements IMcpTool {

    private final InfobaseRegistry registry;

    @Inject
    public ListInfobasesTool(InfobaseRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "list_infobases"; }
    @Override public String description() { return "List infobases registered in 1C:EDT (with optional folder/type filter)"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> folder = new LinkedHashMap<>(); folder.put("type", "string");
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("type", "string"); type.put("enum", List.of("FILE", "SERVER", "WEB"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("folder", folder);
        properties.put("type", type);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public List<Map<String, Object>> call(Map<String, Object> args) throws ToolException {
        String folderFilter = args == null ? null : (String) args.get("folder");
        String typeFilter   = args == null ? null : (String) args.get("type");
        List<Map<String, Object>> out = new ArrayList<>();
        for (InfobaseReference ref : registry.listAll()) {
            InfobaseType t = ref.getInfobaseType();
            if (typeFilter != null && (t == null || !typeFilter.equals(t.name()))) continue;
            if (folderFilter != null && !folderFilter.equals(ref.getFolder())) continue;
            out.add(serialise(ref));
        }
        return out;
    }

    static Map<String, Object> serialise(InfobaseReference ref) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", ref.getName());
        entry.put("uuid", ref.getUuid() == null ? null : ref.getUuid().toString());
        InfobaseType t = ref.getInfobaseType();
        entry.put("type", t == null ? null : t.name());
        IConnectionString cs = ref.getConnectionString();
        entry.put("connection", cs == null ? null : cs.asConnectionString());
        entry.put("folder", ref.getFolder());
        entry.put("isShown", ref.isShowInList());
        return entry;
    }
}
