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

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MIN_TIMEOUT_SECONDS = 10;
    private static final int MAX_TIMEOUT_SECONDS = 600;

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
        timeout.put("type", "integer");
        timeout.put("minimum", MIN_TIMEOUT_SECONDS);
        timeout.put("maximum", MAX_TIMEOUT_SECONDS);

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
        Duration timeout = Duration.ofSeconds(parseTimeout(args));

        Path locPath = Paths.get(location);
        // Замок машинного уровня: список баз — один файл на пользователя
        // (%APPDATA%\1C\1CEStart\ibases.v8i), он переписывается целиком, и
        // блокировки нет ни у нас, ни в EDT. Замок закрывает наши гонки; тот же
        // файл переписывают сам EDT и 1cestart, и это вне нашего контроля.
        String holder = "create_infobase name=" + name + " pid=" + ProcessHandle.current().pid();
        InfobaseReference ref;
        try (ru.fedukhin.edt.mcp.core.ipc.InterProcessLock lock =
                 ru.fedukhin.edt.mcp.core.ipc.InterProcessLock.acquire(
                     "ibases-v8i", holder, timeout)) {
            ref = registry.createFileInfobase(name, locPath, version, folder, timeout);
        } catch (ru.fedukhin.edt.mcp.core.ipc.LockTimeoutException e) {
            throw new ToolException(e.getMessage());
        } catch (java.io.IOException e) {
            throw new ToolException("не удалось взять замок списка информационных баз: "
                + e.getMessage(), e);
        }
        return ListInfobasesTool.serialise(ref);
    }

    /**
     * Границы объявлены и в inputSchema, но schema-валидация на стороне сервера не выполняется —
     * значение доходит до кода как есть. Без проверки {@code timeoutSeconds:0} превратился бы в
     * нулевой бюджет для 1cv8 CREATEINFOBASE, а строка — молча в дефолтные 60с.
     */
    private static int parseTimeout(Map<String, Object> args) throws ToolException {
        Object t = (args == null) ? null : args.get("timeoutSeconds");
        if (t == null) return DEFAULT_TIMEOUT_SECONDS;
        if (!(t instanceof Number n)) {
            throw new ToolException("timeoutSeconds must be an integer");
        }
        int v = n.intValue();
        if (v < MIN_TIMEOUT_SECONDS || v > MAX_TIMEOUT_SECONDS) {
            throw new ToolException("timeoutSeconds must be in ["
                + MIN_TIMEOUT_SECONDS + ", " + MAX_TIMEOUT_SECONDS + "], got " + v);
        }
        return v;
    }

    public static String stringArg(Map<String, Object> args, String key) throws ToolException {
        Object v = (args == null) ? null : args.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new ToolException("missing or empty '" + key + "' argument");
        }
        return (String) v;
    }
}
