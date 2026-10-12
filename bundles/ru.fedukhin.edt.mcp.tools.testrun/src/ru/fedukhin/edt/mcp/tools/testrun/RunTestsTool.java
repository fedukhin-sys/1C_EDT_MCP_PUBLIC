package ru.fedukhin.edt.mcp.tools.testrun;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.CreateInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.testrun.internal.SessionErrorProbe;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestCatalog;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestResult;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunResult;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestSelectorEncoder;

public class RunTestsTool implements IMcpTool {

    /** Общая часть описания run_tests и run_test_method. */
    static final String RUN_BEHAVIOUR = "Only this project's runner executes the tests, even if "
        + "other extensions of the infobase have runners too (runners installed by an older "
        + "plugin ignore this — re-run install_test_runner and deploy). The run returns as soon "
        + "as the result is written, even if a test left a modal window open (the client is "
        + "then closed forcibly, see 'warning'), and fails fast when the run session shows an "
        + "unhandled error in the event log (e.g. a module that compiles in EDT but not at "
        + "runtime) instead of waiting for the timeout.";

    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 3600;

    private final TestRunnerLauncher launcher;
    private final InfobaseRegistry infobaseRegistry;
    private final RuntimeCli runtimeCli;
    private final TestRunnerInstaller.ModuleScaffolder scaffolder;
    private final com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager assoc;

    @Inject
    public RunTestsTool(TestRunnerLauncher launcher, InfobaseRegistry infobaseRegistry,
                        RuntimeCli runtimeCli, TestRunnerInstaller.ModuleScaffolder scaffolder,
            com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager assoc) {
        this.launcher = launcher;
        this.infobaseRegistry = infobaseRegistry;
        this.runtimeCli = runtimeCli;
        this.scaffolder = scaffolder;
        this.assoc = assoc;
    }

    /** Старый seam: без менеджера ассоциаций сверка даёт предупреждение, но не отказ. */
    public RunTestsTool(TestRunnerLauncher launcher, InfobaseRegistry infobaseRegistry,
                        RuntimeCli runtimeCli, TestRunnerInstaller.ModuleScaffolder scaffolder) {
        this(launcher, infobaseRegistry, runtimeCli, scaffolder, null);
    }

    @Override public String name() { return "run_tests"; }
    @Override public String description() {
        return "Run xUnit tests in module (or all modules) against a deployed infobase. "
             + "Tests are taken from the project sources: a test module is a common module with "
             + "ИсполняемыеСценарии/ExecutableScenarios; its tests are those registered via "
             + "ЮнитТесты.ДобавитьТест(\"...\"), or, if none are registered, exported no-arg "
             + "procedures named Тест_*/Test_*. Modules named «…Клиент» run only one by one via "
             + "run_test_method (skipped in the all-modules run). An empty set is an error. "
             + "Requires install_test_runner scaffolding in the project (checked up front). "
             + "Pass user/password if the infobase has users; without them 1cv8 relies on OS "
             + "authentication and otherwise blocks on a login dialog until the timeout. "
             + RUN_BEHAVIOUR;
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> proj = new LinkedHashMap<>(); proj.put("type", "string");
        Map<String, Object> ib = new LinkedHashMap<>(); ib.put("type", "string");
        Map<String, Object> mod = new LinkedHashMap<>(); mod.put("type", "string");
        Map<String, Object> user = new LinkedHashMap<>(); user.put("type", "string");
        Map<String, Object> password = new LinkedHashMap<>(); password.put("type", "string");
        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer"); timeout.put("minimum", MIN_TIMEOUT_SECONDS); timeout.put("maximum", MAX_TIMEOUT_SECONDS);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", proj);
        properties.put("infobase", ib);
        properties.put("moduleFqn", mod);
        properties.put("user", user);
        properties.put("password", password);
        properties.put("timeoutSeconds", timeout);
        Map<String, Object> allowForeign = new LinkedHashMap<>();
        allowForeign.put("type", "boolean");
        allowForeign.put("description", "Allow running against an infobase the project is not associated with");
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
        String moduleFqn = (args != null && args.get("moduleFqn") instanceof String ms) ? ms : null;
        String user = (args != null && args.get("user") instanceof String us) ? us : null;
        String password = (args != null && args.get("password") instanceof String ps) ? ps : null;
        int timeoutSeconds = parseTimeout(args);

        IProject iproject = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!iproject.exists() || !iproject.isOpen()) {
            throw new ToolException("project '" + projectName + "' not open");
        }
        String runner = requireRunnerInstalled(scaffolder, iproject, projectName);
        InfobaseReference ref = infobaseRegistry.findByName(infobaseName).orElseThrow(() ->
            new ToolException("infobase '" + infobaseName + "' not found"));
        String exe = runtimeCli.resolveExecutableForInfobase(ref);
        if (exe == null) {
            throw new ToolException("1cv8 executable not found for infobase '" + infobaseName + "'");
        }

        // Состав прогона — по исходникам проекта: перечислить тесты в рантайме раннеру нечем.
        // Пустой набор — отказ сразу, а не молчаливые «0 тестов» после запуска клиента.
        TestCatalog.Selection selection =
            TestCatalog.collect(Path.of(iproject.getLocation().toOSString()), moduleFqn);
        Path resultFile = TestRunnerLauncher.allocateResultFile();
        Path listFile = writeTestList(resultFile, selection.tests());
        String selector = (moduleFqn == null)
            ? TestSelectorEncoder.encodeAll(resultFile.toString(), runner, listFile.toString())
            : TestSelectorEncoder.encodeModule(moduleFqn, resultFile.toString(), runner, listFile.toString());
        // Прогон тестов пишет документы в базу: уехать не в ту так же разрушительно,
        // как задеплоить не туда. Сверяем с ассоциацией проекта.
        Object af = args == null ? null : args.get("allowForeignInfobase");
        boolean allowForeign = af instanceof Boolean ? (Boolean) af : false;
        java.util.Optional<String> guardWarning = ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseGuard
            .check(projectName, infobaseName,
                   ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseGuard.associatedNames(assoc, iproject),
                   allowForeign);

        // Замок по базе: два параллельных прогона по одной ИБ пишут в неё документы
        // и портят друг другу данные. Файлы результатов при этом не конфликтуют
        // (имена — UUID), поэтому расхождение выглядело бы как плавающие падения.
        String lockKey = ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseLockKey.of(ref);
        String holder = "run_tests project=" + projectName + " pid=" + ProcessHandle.current().pid();
        TestRunResult res;
        try (ru.fedukhin.edt.mcp.core.ipc.InterProcessLock lock =
                 ru.fedukhin.edt.mcp.core.ipc.InterProcessLock.acquire(
                     lockKey, holder, java.time.Duration.ofSeconds(timeoutSeconds))) {
            res = launcher.runWithTimeout(exe, ref.getConnectionString().asConnectionString(),
                selector, timeoutSeconds, user, password, SessionErrorProbe.forInfobase(ref, user));
        } catch (ru.fedukhin.edt.mcp.core.ipc.LockTimeoutException e) {
            throw new ToolException(e.getMessage());
        } catch (java.io.IOException e) {
            throw new ToolException("не удалось взять замок '" + lockKey + "': " + e.getMessage(), e);
        }
        if (res.tests().isEmpty()) {
            throw new ToolException("раннер вернул 0 тестов из " + selection.tests().size()
                + ": обычно раннер в базе старого образца и не знает списка тестов —"
                + " install_test_runner и deploy_project проекта, затем повторить; иначе клиент"
                + " не прочитал файл списка " + listFile);
        }
        Map<String, Object> out = toMap(res);
        if (!selection.skippedClientModules().isEmpty()) {
            addWarning(out, "клиентские тестовые модули пропущены (их тесты — по одному через"
                + " run_test_method): " + String.join(", ", selection.skippedClientModules()));
        }
        guardWarning.ifPresent(w -> addWarning(out, w));
        return out;
    }

    /** Файл списка тестов «Модуль.Метод» по строке рядом с файлом результата (UTF-8). */
    static Path writeTestList(Path resultFile, List<String> tests) throws ToolException {
        Path listFile = resultFile.resolveSibling(
            resultFile.getFileName().toString().replace(".json", ".tests.txt"));
        try {
            java.nio.file.Files.writeString(listFile, String.join("\r\n", tests) + "\r\n",
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new ToolException("не удалось записать список тестов " + listFile + ": " + e.getMessage(), e);
        }
        return listFile;
    }

    /**
     * Fail-fast: без модулей раннера 1cv8 ENTERPRISE стартует, селектор никто не читает,
     * клиент висит окном до таймаута (минуты) — вместо этого сразу говорим, чего не хватает.
     * Наличие модулей в проекте не гарантирует, что раннер задеплоен в ИБ, — но отсекает
     * основной случай «забыли install_test_runner».
     *
     * @return имя серверного модуля раннера проекта — адресат прогона (ключ {@code run}
     *         селектора): раннеры других расширений той же базы селектор пропустят
     */
    public static String requireRunnerInstalled(TestRunnerInstaller.ModuleScaffolder scaffolder,
                                              IProject project, String projectName) throws ToolException {
        // Признаём оба образца имён: раннер, задеплоенный до введения суффикса,
        // продолжает работать — принудительная переустановка тут ничего не даёт,
        // потому что имя модуля важно внутри базы, а не для запуска.
        if (scaffolder.exists(project, "CommonModule." + TestRunnerInstaller.clientModule(projectName))
            && scaffolder.exists(project, "CommonModule." + TestRunnerInstaller.serverModule(projectName))) {
            return TestRunnerInstaller.serverModule(projectName);
        }
        if (scaffolder.exists(project, "CommonModule." + TestRunnerInstaller.CLIENT_MODULE)
            && scaffolder.exists(project, "CommonModule." + TestRunnerInstaller.SERVER_MODULE)) {
            return TestRunnerInstaller.SERVER_MODULE;
        }
        throw new ToolException("xUnit test runner is not installed in project '" + projectName
            + "': run install_test_runner, deploy_project the project, then retry");
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

    public static Map<String, Object> toMap(TestRunResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", r.passed());
        out.put("failed", r.failed());
        out.put("durationMs", r.durationMs());
        List<Map<String, Object>> tests = new ArrayList<>();
        for (TestResult t : r.tests()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("module", t.module());
            m.put("name", t.name());
            m.put("status", t.status());
            m.put("durationMs", t.durationMs());
            if (t.message() != null) m.put("message", t.message());
            tests.add(m);
        }
        out.put("tests", tests);
        if (r.warning() != null) out.put("warning", r.warning());
        return out;
    }

    /** Дописывает предупреждение к уже имеющемуся, а не затирает его. */
    static void addWarning(Map<String, Object> out, String warning) {
        out.merge("warning", warning, (a, b) -> a + "; " + b);
    }

    /** Assert-сообщения xUnit формируются в 1С и содержат реальные значения базы. */
    @Override public boolean returnsInfobaseData() { return true; }
}
