package ru.fedukhin.edt.mcp.tools.infobase;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.version.Version;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.core.resources.IProject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.ipc.InterProcessLock;
import ru.fedukhin.edt.mcp.core.ipc.LockTimeoutException;
import ru.fedukhin.edt.mcp.core.jobs.JobContext;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseLockKey;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SchemaBuilder;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ServerInfobaseParams;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ToolArgs;

/**
 * Сценарий 1: под созданный вручную проект конфигурации создаётся новая база (файловая или
 * серверная), в неё загружается {@code .dt}, проект привязывается к базе и обновляется из неё.
 * Автоотката нет: при сбое созданная база остаётся, результат задания говорит, на каком шаге.
 */
public class CreateInfobaseFromDtTool implements IMcpTool {

    static final int DEFAULT_TIMEOUT_MINUTES = 240;
    static final Duration CREATE_TIMEOUT = Duration.ofMinutes(10);
    static final Duration INFOBASE_LIST_LOCK_WAIT = Duration.ofSeconds(60);

    private final InfobaseRegistry registry;
    private final InfobaseTargets targets;
    private final SyncV2 syncV2;
    private final ProjectFromInfobaseUpdater updater;
    private final ThickClientOps ops;
    private final InfobaseJobs jobs;

    @Inject
    public CreateInfobaseFromDtTool(InfobaseRegistry registry, InfobaseTargets targets, SyncV2 syncV2,
                                    ProjectFromInfobaseUpdater updater, ThickClientOps ops, InfobaseJobs jobs) {
        this.registry = registry;
        this.targets = targets;
        this.syncV2 = syncV2;
        this.updater = updater;
        this.ops = ops;
        this.jobs = jobs;
    }

    @Override public String name() { return "create_infobase_from_dt"; }

    @Override public String description() {
        return "For a freshly created configuration project: create a new FILE or SERVER infobase (SERVER: "
            + "1C cluster + DBMS database; scheduled jobs locked by default), load a .dt into it, associate the "
            + "project with it and pull the configuration into the project (project content is replaced). "
            + "SERVER: dbName (default = ref) must name a NEW database — protected: if the DBMS already has a "
            + "database with this name, 1C attaches the new infobase to it; the tool checks the new infobase right "
            + "after creation and refuses before loading the .dt (nothing is registered in EDT; remove the cluster "
            + "registration without dropping the database and retry with a new dbName). Background job: returns "
            + "jobId, poll get_job_status. Requires 1C:EDT 2026.1+.";
    }

    @Override public Map<String, Object> inputSchema() {
        return SchemaBuilder.object()
            .string("project", "Configuration project created in 1C:EDT", true)
            .string("dtFile", "Absolute path to the .dt file", true)
            .stringEnum("type", "Infobase kind", List.of("FILE", "SERVER"), true)
            .string("infobase", "Name in the EDT infobase list; default is the project name", false)
            .string("location", "FILE: absolute path of the infobase directory (must not exist or be empty)", false)
            .string("server", "SERVER: 1C cluster host (default localhost)", false)
            .string("ref", "SERVER: infobase name in the cluster (default = infobase)", false)
            .stringEnum("dbms", "SERVER: DBMS (default PostgreSQL)", ServerInfobaseParams.DBMS_TYPES, false)
            .string("dbServer", "SERVER: DBMS host (default = server)", false)
            .string("dbName", "SERVER: database name (default = ref); must be a NEW database — protected: if the "
                + "DBMS already has a database with this name, 1C attaches the new infobase to it, and the job "
                + "refuses right after creation, before loading the .dt", false)
            .string("dbUser", "SERVER: DBMS user", false)
            .string("dbPassword", "SERVER: DBMS password", false)
            .bool("createDatabase", "SERVER: create the database if absent (default true)")
            .bool("lockScheduledJobs", "SERVER: lock scheduled jobs in the new infobase (default true)")
            .string("clusterUser", "SERVER: 1C cluster administrator, if the cluster has one", false)
            .string("clusterPassword", "SERVER: 1C cluster administrator password", false)
            .string("version", "Platform version (8.3.27 or full build 8.3.27.2214); default is the project version", false)
            .string("user", "Infobase user from the .dt; saved to EDT access settings of the new infobase", false)
            .string("password", "Password of the infobase user", false)
            .bool("discardProjectChanges", "Proceed even if the project is already associated with other infobases")
            .integer("timeoutMinutes", "Job time limit, minutes (default 240)", 1, 1440)
            .build();
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = ToolArgs.required(args, "project");
        IProject project = targets.openProject(projectName);
        IConfigurationProject configuration = targets.configurationProject(project);
        Path dtFile = ToolArgs.existingFile("dtFile", ToolArgs.required(args, "dtFile"), "dt");
        String type = ToolArgs.required(args, "type");
        if (!"FILE".equals(type) && !"SERVER".equals(type)) {
            throw new ToolException("type must be FILE or SERVER, got '" + type + "'");
        }
        String infobaseName = orDefault(ToolArgs.optional(args, "infobase"), projectName);
        if (registry.findByName(infobaseName).isPresent()) {
            throw new ToolException("база '" + infobaseName + "' уже есть в списке баз EDT. Для перезаливки "
                + "существующей базы используйте restore_infobase_from_dt, либо задайте другое имя (infobase)");
        }
        syncV2.requireAvailable();
        if (!ToolArgs.flag(args, "discardProjectChanges", false)) {
            List<String> linked = targets.associatedInfobases(project);
            if (!linked.isEmpty()) {
                throw new ToolException("проект '" + projectName + "' уже связан с базами " + linked
                    + " — это не новый проект. Для существующих проектов есть restore_infobase_from_dt и "
                    + "update_project_from_infobase; чтобы всё же заменить содержимое проекта конфигурацией "
                    + "из .dt, повторите с discardProjectChanges: true");
            }
        }
        String version = ToolArgs.optional(args, "version");
        if (version == null) {
            Version projectVersion = configuration.getVersion();
            if (projectVersion == null) {
                throw new ToolException("у проекта '" + projectName + "' не определена версия платформы — передайте version");
            }
            version = projectVersion.toString();
        }
        Path location = null;
        ServerInfobaseParams server = null;
        if ("FILE".equals(type)) {
            location = fileLocation(ToolArgs.required(args, "location"));
        } else {
            server = serverParams(args, infobaseName);
            server.validate();
        }
        String user = ToolArgs.optional(args, "user");
        String password = ToolArgs.optional(args, "password");
        Duration timeout = Duration.ofMinutes(
            ToolArgs.integer(args, "timeoutMinutes", DEFAULT_TIMEOUT_MINUTES, 1, 1440));

        // Замок — по UUID будущей базы: он выбирается заранее и потом становится её UUID в EDT,
        // так что deploy_project и run_tests других инстанций получат тот же ключ.
        UUID uuid = UUID.randomUUID();
        String lockKey = InfobaseLockKey.build(uuid.toString(), null, infobaseName);

        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("project", projectName);
        echo.put("infobase", infobaseName);
        echo.put("type", type);
        echo.put("dtFile", dtFile.toString());

        String finalVersion = version;
        Path finalLocation = location;
        ServerInfobaseParams finalServer = server;
        return jobs.start(name(), "новая база " + infobaseName + " из " + dtFile.getFileName(), lockKey,
            projectName, timeout, ctx -> run(ctx, project, infobaseName, uuid, type, finalLocation, finalServer,
                finalVersion, dtFile, user, password), echo);
    }

    private void run(JobContext ctx, IProject project, String infobaseName, UUID uuid, String type, Path location,
                     ServerInfobaseParams server, String version, Path dtFile, String user, String password)
            throws Exception {
        ctx.put("project", project.getName());
        ctx.put("infobase", infobaseName);
        ctx.put("type", type);
        ctx.put("version", version);
        // .dt мог прийти из рабочей базы: флаг ПДн — в fail-closed по имени и по заранее выбранному UUID.
        // Флаги при удалении базы не чистятся, и false прежней базы с тем же именем не должен пережить
        // прерванное задание. Первый сброс — ещё ДО создания: CREATEINFOBASE монитор не отменяет, и
        // отмена или лимит времени на нём оставили бы следующие шаги невыполненными. Второй — прямо
        // перед загрузкой .dt (дёшево и идемпотентно).
        InfobaseJobs.resetPiiFlag(ctx, infobaseName, uuid);
        InfobaseReference infobase = ctx.step("create-infobase",
            () -> create(infobaseName, uuid, location, server, version));
        ctx.put("connection", connectionOf(infobase));
        InfobaseJobs.resetPiiFlag(ctx, infobase.getName(), uuid);
        ctx.step("restore-dt", () -> {
            ops.restoreDt(project, infobase, dtFile, ctx.monitor());
            return null;
        });
        if (user != null) {
            ctx.step("access-settings", () -> {
                ops.storeCredentials(infobase, user, password);
                return null;
            });
        }
        ctx.step("associate", () -> {
            targets.associate(project, infobase, true);
            return null;
        });
        // Новый или несвязанный проект заменяется целиком (полная перезагрузка) — флаг явно true.
        ProjectFromInfobaseUpdater.Outcome outcome = InfobaseJobs.updateProject(ctx, updater, project, infobase, true);
        Map<String, Object> projectUpdate = new LinkedHashMap<>();
        projectUpdate.put("resolution", outcome.resolution());
        projectUpdate.put("durationMs", outcome.durationMs());
        if (outcome.warning() != null) projectUpdate.put("warning", outcome.warning());
        ctx.put("projectUpdate", projectUpdate);
        List<String> extensions = ctx.step("list-extensions",
            () -> ops.listExtensions(project, infobase, ctx.monitor()));
        ctx.put("extensionsInInfobase", extensions);
    }

    /** Создание и регистрация — под замком списка баз, как у create_infobase. */
    private InfobaseReference create(String name, UUID uuid, Path location, ServerInfobaseParams server,
                                     String version) throws ToolException {
        String holder = "create_infobase_from_dt infobase=" + name + " pid=" + ProcessHandle.current().pid();
        try (InterProcessLock lock = InterProcessLock.acquire("ibases-v8i", holder, INFOBASE_LIST_LOCK_WAIT)) {
            if (registry.findByName(name).isPresent()) {
                throw new ToolException("база '" + name + "' уже появилась в списке баз EDT");
            }
            return location != null
                ? registry.createFileInfobase(name, uuid, location, version, null, CREATE_TIMEOUT)
                : registry.createServerInfobase(name, uuid, server, version, null, CREATE_TIMEOUT);
        } catch (LockTimeoutException e) {
            throw new ToolException(e.getMessage());
        } catch (IOException e) {
            throw new ToolException("не удалось взять замок списка информационных баз: " + e.getMessage(), e);
        }
    }

    private static ServerInfobaseParams serverParams(Map<String, Object> args, String infobaseName)
            throws ToolException {
        String server = orDefault(ToolArgs.optional(args, "server"), "localhost");
        String ref = orDefault(ToolArgs.optional(args, "ref"), infobaseName);
        return new ServerInfobaseParams(server, ref,
            orDefault(ToolArgs.optional(args, "dbms"), "PostgreSQL"),
            orDefault(ToolArgs.optional(args, "dbServer"), server),
            orDefault(ToolArgs.optional(args, "dbName"), ref),
            ToolArgs.optional(args, "dbUser"),
            ToolArgs.optional(args, "dbPassword"),
            ToolArgs.flag(args, "createDatabase", true),
            ToolArgs.flag(args, "lockScheduledJobs", true),
            ToolArgs.optional(args, "clusterUser"),
            ToolArgs.optional(args, "clusterPassword"));
    }

    private static Path fileLocation(String value) throws ToolException {
        Path path;
        try {
            path = Paths.get(value);
        } catch (InvalidPathException e) {
            throw new ToolException("'location': некорректный путь '" + value + "'");
        }
        if (!path.isAbsolute()) throw new ToolException("'location': нужен абсолютный путь, получен '" + value + "'");
        if (Files.exists(path)) {
            try (Stream<Path> children = Files.list(path)) {
                if (children.findAny().isPresent()) {
                    throw new ToolException("'location': каталог не пуст: " + value);
                }
            } catch (IOException e) {
                throw new ToolException("'location': не удалось прочитать каталог: " + e.getMessage(), e);
            }
        }
        return path;
    }

    private static String connectionOf(InfobaseReference infobase) {
        IConnectionString cs = infobase.getConnectionString();
        return cs == null ? null : cs.asConnectionString();
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
