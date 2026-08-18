package ru.fedukhin.edt.mcp.tools.infobase;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseDeployer;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

public class DeployProjectTool implements IMcpTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 3600;

    /**
     * A real 1cv8 configuration update never completes this fast. A successful
     * deploy below this duration is a BUG-18 no-op — 1cv8 was not actually run.
     */
    private static final long SUSPICIOUS_DEPLOY_MS = 5_000L;

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final InfobaseRegistry registry;
    private final InfobaseDeployer deployer;
    private final com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager assoc;

    @Inject
    public DeployProjectTool(InfobaseRegistry registry, InfobaseDeployer deployer,
            com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager assoc) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), registry, deployer, assoc);
    }

    /** Старый seam: без менеджера ассоциаций сверка даёт предупреждение, но не отказ. */
    public DeployProjectTool(Supplier<IWorkspaceRoot> rootSupplier,
                             InfobaseRegistry registry, InfobaseDeployer deployer) {
        this(rootSupplier, registry, deployer, null);
    }

    public DeployProjectTool(Supplier<IWorkspaceRoot> rootSupplier,
                             InfobaseRegistry registry, InfobaseDeployer deployer,
            com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager assoc) {
        this.rootSupplier = rootSupplier;
        this.registry = registry;
        this.deployer = deployer;
        this.assoc = assoc;
    }

    @Override public String name() { return "deploy_project"; }
    @Override public String description() { return "Update infobase configuration from a project (sync project → infobase)"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> proj = new LinkedHashMap<>(); proj.put("type", "string");
        Map<String, Object> ib = new LinkedHashMap<>(); ib.put("type", "string");
        Map<String, Object> force = new LinkedHashMap<>(); force.put("type", "boolean");
        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer");
        timeout.put("minimum", MIN_TIMEOUT_SECONDS);
        timeout.put("maximum", MAX_TIMEOUT_SECONDS);
        Map<String, Object> allowForeign = new LinkedHashMap<>();
        allowForeign.put("type", "boolean");
        allowForeign.put("description", "Allow deploying to an infobase the project is not associated with");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", proj);
        properties.put("infobase", ib);
        properties.put("force", force);
        properties.put("timeoutSeconds", timeout);
        properties.put("allowForeignInfobase", allowForeign);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "infobase"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = CreateInfobaseTool.stringArg(args, "project");
        String infobaseName = CreateInfobaseTool.stringArg(args, "infobase");
        Object f = args == null ? null : args.get("force");
        boolean force = f instanceof Boolean ? (Boolean) f : false;
        int timeoutSeconds = parseTimeout(args);

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not open");
        }
        InfobaseReference ref = registry.findByName(infobaseName).orElseThrow(() ->
            new ToolException("infobase '" + infobaseName + "' not found"));

        // Список баз общий на пользователя, а цель резолвится по имени: без сверки
        // с ассоциацией одна опечатка уводит расширение в чужую базу без ошибок.
        Object af = args == null ? null : args.get("allowForeignInfobase");
        boolean allowForeign = af instanceof Boolean ? (Boolean) af : false;
        java.util.Optional<String> guardWarning = ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseGuard
            .check(projectName, infobaseName,
                   ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseGuard.associatedNames(assoc, project),
                   allowForeign);

        // Замок по базе: две инстанции EDT, деплоящие в одну ИБ, идут в 1cv8
        // одновременно и получают тихий no-op вместо обновления конфигурации.
        String lockKey = ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseLockKey.of(ref);
        String holder = "deploy_project project=" + projectName + " ws=" + workspaceLeaf()
            + " pid=" + ProcessHandle.current().pid();
        InfobaseDeployer.DeployResult res;
        try (ru.fedukhin.edt.mcp.core.ipc.InterProcessLock lock =
                 ru.fedukhin.edt.mcp.core.ipc.InterProcessLock.acquire(
                     lockKey, holder, java.time.Duration.ofSeconds(timeoutSeconds))) {
            res = deployer.deployWithTimeout(project, ref, force, timeoutSeconds);
        } catch (ru.fedukhin.edt.mcp.core.ipc.LockTimeoutException e) {
            throw new ToolException(e.getMessage());
        } catch (java.io.IOException e) {
            throw new ToolException("не удалось взять замок '" + lockKey + "': " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projectName);
        out.put("infobase", infobaseName);
        out.put("deployed", res.ok());
        out.put("durationMs", res.durationMs());
        // BUG-18: a "successful" deploy that returns almost instantly did not
        // actually run 1cv8 — surface it instead of reporting a false success.
        if (res.ok() && res.durationMs() < SUSPICIOUS_DEPLOY_MS) {
            out.put("warning", "deploy returned in " + res.durationMs() + " ms — too fast "
                    + "for a real 1cv8 configuration update (BUG-18 no-op): the configuration "
                    + "was most likely NOT applied. Ensure no Enterprise clients or debug "
                    + "sessions hold infobase '" + infobaseName + "' — including ones started by "
                    + "other 1C:EDT instances (list_running_clients with includeForeign: true) — "
                    + "then retry deploy_project.");
        }
        guardWarning.ifPresent(w -> out.merge("warning", w, (a, b) -> a + " | " + b));
        return out;
    }

    /** Имя каталога рабочей области — чтобы в чужом отказе было видно, чья сессия держит базу. */
    private String workspaceLeaf() {
        org.eclipse.core.runtime.IPath loc = rootSupplier.get().getLocation();
        if (loc == null) return "(unknown)";
        String leaf = loc.lastSegment();
        return leaf == null ? loc.toString() : leaf;
    }

    private static int parseTimeout(Map<String, Object> args) throws ToolException {
        Object t = (args == null) ? null : args.get("timeoutSeconds");
        if (t == null) return DEFAULT_TIMEOUT_SECONDS;
        int v;
        if (t instanceof Number n) v = n.intValue();
        else throw new ToolException("timeoutSeconds must be an integer");
        if (v < MIN_TIMEOUT_SECONDS || v > MAX_TIMEOUT_SECONDS) {
            throw new ToolException("timeoutSeconds must be in ["
                + MIN_TIMEOUT_SECONDS + ", " + MAX_TIMEOUT_SECONDS + "], got " + v);
        }
        return v;
    }
}
