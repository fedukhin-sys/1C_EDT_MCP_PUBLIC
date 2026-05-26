package ru.fedukhin.edt.mcp.tools.edt.workspace;

import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.version.Version;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;

public class ListRuntimeVersionsTool implements IMcpTool {

    private final IRuntimeRegistry runtimeRegistry;

    @Inject
    public ListRuntimeVersionsTool(IRuntimeRegistry runtimeRegistry) {
        this.runtimeRegistry = runtimeRegistry;
    }

    @Override public String name() { return "list_runtime_versions"; }
    @Override public String description() { return "List 1C runtime versions registered in the IDE"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public List<Map<String, Object>> call(Map<String, Object> args) throws ToolException {
        Version maxVersion = null;
        List<IRuntime> runtimes = new ArrayList<>(runtimeRegistry.getRuntimes());
        for (IRuntime r : runtimes) {
            Version v = r.getVersion();
            if (maxVersion == null || v.compareTo(maxVersion) > 0) {
                maxVersion = v;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (IRuntime r : runtimes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            Version v = r.getVersion();
            entry.put("version", v.toString());
            entry.put("isDefault", maxVersion != null && v.equals(maxVersion));
            out.add(entry);
        }
        return out;
    }
}
