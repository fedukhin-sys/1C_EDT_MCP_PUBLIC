package ru.fedukhin.edt.mcp.tools.client;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.internal.InfobaseLookup;
import ru.fedukhin.edt.mcp.tools.client.internal.LaunchConfigService;

/**
 * Список launch-конфигураций «Клиент 1С:Предприятия» из workspace EDT.
 * Пароль ({@code ATTR_LAUNCH_USER_PASSWORD}) наружу не отдаётся никогда —
 * только признак {@code hasPassword}.
 */
public class ListLaunchConfigurationsTool implements IMcpTool {

    private final LaunchConfigService service;
    private final InfobaseLookup lookup;

    @Inject
    public ListLaunchConfigurationsTool(LaunchConfigService service, InfobaseLookup lookup) {
        this.service = service;
        this.lookup = lookup;
    }

    @Override public String name() { return "list_launch_configurations"; }

    @Override public String description() {
        return "List EDT launch configurations of type 'Клиент 1С:Предприятия' (RuntimeClient):"
            + " name, project, infobase, client type, user. Passwords are never returned.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("type", "string");
        project.put("description", "optional filter by EDT project name");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", project);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        String projectFilter = (args == null) ? null : (String) args.get("project");
        List<Map<String, Object>> configurations = new ArrayList<>();
        for (ILaunchConfiguration cfg : service.list()) {
            try {
                String project = cfg.getAttribute(LaunchConfigService.ATTR_PROJECT_NAME, "");
                if (projectFilter != null && !projectFilter.isEmpty() && !projectFilter.equals(project)) {
                    continue;
                }
                configurations.add(describe(cfg, project));
            } catch (CoreException e) {
                throw new ToolException("cannot read launch configuration '" + cfg.getName()
                    + "': " + e.getMessage(), e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configurations", configurations);
        return out;
    }

    private Map<String, Object> describe(ILaunchConfiguration cfg, String project) throws CoreException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", cfg.getName());
        entry.put("project", project.isEmpty() ? null : project);

        String applicationId = cfg.getAttribute(LaunchConfigService.ATTR_APPLICATION_ID, "");
        entry.put("applicationId", applicationId.isEmpty() ? null : applicationId);
        entry.put("infobase", resolveInfobaseName(applicationId));

        boolean autoSelect = cfg.getAttribute(LaunchConfigService.ATTR_CLIENT_AUTO_SELECT, false);
        String clientTypeId = cfg.getAttribute(LaunchConfigService.ATTR_CLIENT_TYPE, "");
        entry.put("clientType", autoSelect ? "auto" : mapClientType(clientTypeId));

        String user = cfg.getAttribute(LaunchConfigService.ATTR_LAUNCH_USER_NAME, "");
        entry.put("user", user.isEmpty() ? null : user);
        entry.put("hasPassword",
            !cfg.getAttribute(LaunchConfigService.ATTR_LAUNCH_USER_PASSWORD, "").isEmpty());
        entry.put("osAuthentication",
            cfg.getAttribute(LaunchConfigService.ATTR_OS_INFOBASE_ACCESS, false));

        entry.put("runtimeInstallation",
            mapRuntime(cfg.getAttribute(LaunchConfigService.ATTR_RUNTIME_INSTALLATION, "")));
        return entry;
    }

    private String resolveInfobaseName(String applicationId) {
        if (applicationId == null || applicationId.isEmpty()) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(applicationId);
        } catch (IllegalArgumentException e) {
            return null;   // не uuid — приложение другого рода, имени ИБ нет
        }
        Optional<InfobaseReference> ref = lookup.findByUuid(uuid);
        return ref.map(InfobaseReference::getName).orElse(null);
    }

    /** {@code …componentTypes.ThinClient} → {@code thin}; незнакомый id возвращается как есть. */
    public static String mapClientType(String componentTypeId) {
        if (componentTypeId == null || componentTypeId.isEmpty()) {
            return null;
        }
        if (componentTypeId.endsWith(".ThinClient")) return "thin";
        if (componentTypeId.endsWith(".ThickClient")) return "thick";
        if (componentTypeId.endsWith(".WebClient")) return "web";
        return componentTypeId;
    }

    /** {@code …runtimeType.EnterprisePlatform=8.3.27} → {@code 8.3.27}; пусто → {@code null} (авто). */
    public static String mapRuntime(String installation) {
        if (installation == null || installation.isEmpty()) {
            return null;
        }
        int eq = installation.lastIndexOf('=');
        return (eq >= 0 && eq < installation.length() - 1) ? installation.substring(eq + 1) : installation;
    }
}
