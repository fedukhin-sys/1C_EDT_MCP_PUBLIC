package ru.fedukhin.edt.mcp.tools.infobase;

import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

public class CreateInfobaseTool implements IMcpTool {

    private final InfobaseRegistry registry;
    private final IInfobaseManager manager;
    private final IRuntimeRegistry runtimeRegistry;

    @Inject
    public CreateInfobaseTool(InfobaseRegistry registry, IInfobaseManager manager,
                              IRuntimeRegistry runtimeRegistry) {
        this.registry = registry;
        this.manager = manager;
        this.runtimeRegistry = runtimeRegistry;
    }

    @Override public String name() { return "create_infobase"; }
    @Override public String description() { return "Create a FILE infobase via 1cv8.exe and register it in 1C:EDT"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> name = new LinkedHashMap<>(); name.put("type", "string");
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("type", "string"); type.put("enum", List.of("FILE"));
        Map<String, Object> location = new LinkedHashMap<>(); location.put("type", "string");
        Map<String, Object> version = new LinkedHashMap<>(); version.put("type", "string");
        Map<String, Object> folder = new LinkedHashMap<>(); folder.put("type", "string");
        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer"); timeout.put("minimum", 10); timeout.put("maximum", 600);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);
        properties.put("type", type);
        properties.put("location", location);
        properties.put("version", version);
        properties.put("folder", folder);
        properties.put("timeoutSeconds", timeout);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name", "type", "location"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String name = stringArg(args, "name");
        String type = stringArg(args, "type");
        String location = stringArg(args, "location");
        if (!"FILE".equals(type)) {
            throw new ToolException("only FILE infobases supported in stage 3");
        }
        String version = (args == null) ? null : (String) args.get("version");
        if (version == null || version.isEmpty()) {
            IRuntime def = runtimeRegistry.getRuntimes().stream()
                .max(Comparator.comparing(IRuntime::getVersion))
                .orElseThrow(() -> new ToolException("no 1C runtime registered"));
            version = String.valueOf(def.getVersion());
        }
        String folder = (args == null) ? null : (String) args.get("folder");
        if (folder == null || folder.isEmpty()) {
            folder = manager.generateGroupName();
        }
        Object t = (args == null) ? null : args.get("timeoutSeconds");
        Duration timeout = Duration.ofSeconds(t instanceof Number ? ((Number) t).longValue() : 60L);

        Path locPath = Paths.get(location);
        InfobaseReference ref = registry.createFileInfobase(name, locPath, version, folder, timeout);
        return ListInfobasesTool.serialise(ref);
    }

    public static String stringArg(Map<String, Object> args, String key) throws ToolException {
        Object v = (args == null) ? null : args.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new ToolException("missing or empty '" + key + "' argument");
        }
        return (String) v;
    }
}
