package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.ipc.McpHome;

/**
 * Wraps the EDT thick-client CLI. Production-mode resolves 1cv8.exe via
 * {@code IRuntimeRegistry → IRuntime → RuntimeInstallation → IRuntimeComponentManager.getComponent}
 * (see SPIKE in Stage 3 plan Task 3 step 1). Tests inject a {@link ExecutableResolver} +
 * {@link ProcessFactory} pair to exercise the orchestration without launching a real process.
 */
public class RuntimeCli {

    /** Resolves the 1cv8.exe File for a given version string. */
    public interface ExecutableResolver {
        File resolve(String version) throws ToolException;
    }

    /** Builds & starts a Process for the given command line and working directory. */
    public interface ProcessFactory {
        Process start(List<String> command, File workingDir) throws IOException;
    }

    private final IRuntimeRegistry registry;
    private final ExecutableResolver executableResolver;
    private final ProcessFactory processFactory;
    private final DesignerBatch batch;

    @Inject
    public RuntimeCli(IRuntimeRegistry registry,
                       IResolvableRuntimeInstallationManager installationManager,
                       IRuntimeComponentManager componentManager) {
        this(registry,
             new DefaultExecutableResolver(installationManager, componentManager),
             new DefaultProcessFactory());
    }

    public RuntimeCli(IRuntimeRegistry registry, ExecutableResolver er, ProcessFactory pf) {
        this.registry = registry;
        this.executableResolver = er;
        this.processFactory = pf;
        this.batch = new DesignerBatch(pf, DesignerBatch.DEFAULT_POLL);
    }

    /**
     * Run {@code 1cv8.exe CREATEINFOBASE File=<location>} synchronously.
     * Returns process exit code (always 0 on success — non-zero throws).
     */
    public int createFileInfobase(Path location, String version, Duration timeout) throws ToolException {
        requireRegisteredVersion(version);
        File executable = executableResolver.resolve(version);
        // Без кавычек вокруг пути: ProcessBuilder на Windows экранирует встроенные " как \",
        // а 1cv8.exe такую форму не понимает — отвечает «Неопределена информационная база»
        // (тот же отказ описан в TestRunnerLauncher.buildCommand). Кавычки вокруг аргумента
        // с пробелами Windows расставляет сам.
        return runCreateInfobase(executable, "File=" + location, timeout, List.of());
    }

    /**
     * Run {@code 1cv8.exe CREATEINFOBASE Srvr=…;Ref=…;DBMS=…} — серверная база в кластере 1С.
     * С {@code CrSQLDB=Y} платформа сама создаёт базу данных в СУБД — а если БД с таким именем в СУБД
     * уже есть, молча подключает новую базу к ней. Поэтому сразу после создания новая база
     * проверяется тем же 1cv8 ({@link #requireNewDatabase}): загрузка {@code .dt} в подключённую
     * чужую БД уничтожила бы её данные (живой прогон 1.24.0).
     */
    public int createServerInfobase(ServerInfobaseParams params, String version, Duration timeout)
            throws ToolException {
        params.validate();
        List<String> secrets = new ArrayList<>();
        if (params.dbPassword() != null) secrets.add(params.dbPassword());
        if (params.clusterPassword() != null) secrets.add(params.clusterPassword());
        requireRegisteredVersion(version);
        File executable = executableResolver.resolve(version);
        int code = runCreateInfobase(executable, params.toCreateInfobaseArgument(), timeout, secrets);
        requireNewDatabase(executable, params, timeout, secrets);
        return code;
    }

    /**
     * Свежая база, созданная CREATEINFOBASE, конфигурации не содержит: выгрузка
     * {@code DESIGNER /S <сервер>\<база> /DumpConfigToFiles <каталог> -configDumpInfoOnly} даёт
     * {@code ConfigDumpInfo.xml} с пустым {@code <ConfigVersions/>}. Любой элемент {@code <Metadata},
     * отсутствие файла или отказ конфигуратора (у существующей базы с пользователями — «Пользователь ИБ
     * не идентифицирован») значат: 1С подключила новую базу к БД, которая в СУБД уже была, — дальше
     * нельзя. Проверка fail-closed: файл без {@code <ConfigVersions} или без закрывающего
     * {@code </ConfigDumpInfo>} (пустой, обрезанный) — тоже отказ. Учётные данные проверке не нужны: у
     * свежей базы нет ни пользователей, ни агента EDT.
     */
    private void requireNewDatabase(File executable, ServerInfobaseParams params, Duration timeout,
                                    List<String> secrets) throws ToolException {
        Path dumpDir = newTempDirectory();
        try {
            DesignerBatch.Result result;
            try {
                result = batch.run(executable,
                    List.of("/S", params.server() + "\\" + params.ref(), "/DumpConfigToFiles", dumpDir.toString(),
                        "-configDumpInfoOnly"),
                    null, timeout);
            } catch (ToolException e) {
                throw notNewDatabase(params, maskSecrets(e.getMessage(), secrets));
            }
            if (result.exitCode() != 0) {
                String reason = maskSecrets(result.output(), secrets);
                throw notNewDatabase(params, "проверка новой базы конфигуратором не прошла (код " + result.exitCode()
                    + (reason.isEmpty() ? "" : ": " + reason) + ")");
            }
            Path info = dumpDir.resolve("ConfigDumpInfo.xml");
            if (!Files.isRegularFile(info)) {
                throw notNewDatabase(params, "проверка новой базы не выгрузила ConfigDumpInfo.xml");
            }
            String dump;
            try {
                dump = new String(Files.readAllBytes(info), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw notNewDatabase(params, "не удалось прочитать ConfigDumpInfo.xml проверки: " + e.getMessage());
            }
            // Fail-closed: пустой, обрезанный или непонятный файл — не «конфигурации нет», а отказ.
            if (!dump.contains("<ConfigVersions") || !dump.contains("</ConfigDumpInfo>")) {
                throw notNewDatabase(params, "ConfigDumpInfo.xml проверки пуст или повреждён: нет элемента "
                    + "ConfigVersions или конца файла");
            }
            if (dump.contains("<Metadata")) {
                throw notNewDatabase(params, null);
            }
        } finally {
            deleteQuietly(dumpDir);
        }
    }

    /**
     * @param probeFailure причина, по которой проверка не состоялась; {@code null} — проверка прошла и
     *     нашла в новой базе конфигурацию
     */
    private static ToolException notNewDatabase(ServerInfobaseParams params, String probeFailure) {
        String what = probeFailure == null
            ? "в СУБД уже была база данных '" + params.dbName() + "': 1С подключила к ней новую информационную "
                + "базу '" + params.ref() + "' (в ней оказалась конфигурация)"
            : "не удалось убедиться, что база данных '" + params.dbName() + "' новая (" + probeFailure + "): "
                + "возможно, она уже была в СУБД, и 1С подключила к ней новую информационную базу '"
                + params.ref() + "'";
        return new ToolException(what + ". Загрузка .dt уничтожила бы её данные — задание остановлено до "
            + "загрузки, в EDT база не зарегистрирована. Удалите регистрацию '" + params.ref() + "' в кластере 1С, "
            + "НЕ удаляя базу данных (rac infobase drop без --drop-database или консоль кластера), выберите новое "
            + "dbName и повторите");
    }

    /**
     * @param secrets пароли из строки подключения: 1cv8 может эхом вставить их в текст отказа, и
     *     там они вырезаются ({@link #maskSecrets})
     */
    private int runCreateInfobase(File executable, String connectionArgument, Duration timeout,
                                  List<String> secrets) throws ToolException {
        Path outLog = DesignerBatch.newOutLog();
        // /Out — туда 1cv8 пишет причину отказа (stderr пуст); /DisableStartupDialogs — чтобы на
        // ошибке не повис модальный диалог процесса без окна.
        List<String> cmd = List.of(executable.getAbsolutePath(), "CREATEINFOBASE", connectionArgument,
            "/Out", outLog.toString(), "/DisableStartupDialogs");
        try {
            Process p;
            try {
                p = processFactory.start(cmd, null);
            } catch (IOException e) {
                throw new ToolException("failed to start 1cv8.exe: " + e.getMessage(), e);
            }
            // Дренируем потоки параллельно с ожиданием: полный pipe подвешивает сам процесс, и тогда
            // никакой таймаут не спасёт (та же болезнь, что была у раннера тестов). Читать их до
            // waitFor нельзя — блокирующее чтение и есть источник зависания.
            StringBuilder stderr = new StringBuilder();
            Thread errDrain = DesignerBatch.drainAsync(p.getErrorStream(), stderr);
            Thread outDrain = DesignerBatch.drainAsync(p.getInputStream(), new StringBuilder());
            boolean finished;
            try {
                finished = p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new ToolException("interrupted waiting for 1cv8.exe");
            }
            if (!finished) {
                p.destroyForcibly();
                throw new ToolException("createInfobase timeout after " + timeout.toSeconds() + "s");
            }
            DesignerBatch.joinQuietly(errDrain);
            DesignerBatch.joinQuietly(outDrain);
            int code = p.exitValue();
            if (code != 0) {
                String reason = OutLogReader.read(outLog);
                if (reason.isEmpty()) {
                    synchronized (stderr) {
                        reason = stderr.toString().trim();
                    }
                }
                // 1cv8 иногда эхо́м вставляет в /Out или stderr саму строку подключения — вместе
                // с ней и пароли (ServerInfobaseParams.toCreateInfobaseArgument). Маскируем перед
                // тем, как текст попадёт в сообщение исключения (оно уходит клиенту и в лог).
                reason = maskSecrets(reason, secrets);
                throw new ToolException("1cv8 CREATEINFOBASE exited " + code
                    + (reason.isEmpty() ? "" : ": " + reason));
            }
            return code;
        } finally {
            try {
                Files.deleteIfExists(outLog);
            } catch (IOException ignored) {
                // временный лог; не удалился — не беда
            }
        }
    }

    /**
     * Версия должна быть зарегистрирована в EDT. Принимается и маска ({@code 8.3.27}), и полная
     * сборка ({@code 8.3.27.2214}) — серверной базе нужна ровно та сборка, что у кластера.
     *
     * <p>{@code IRuntimeRegistry.getRuntime(String)} принимает id среды, а не версию, поэтому
     * поиск идёт перебором {@code getRuntimes()} — так отказ получается понятным.
     */
    private void requireRegisteredVersion(String version) throws ToolException {
        for (IRuntime r : registry.getRuntimes()) {
            String known = String.valueOf(r.getVersion());
            if (version.equals(known) || version.startsWith(known + ".")) return;
        }
        throw new ToolException("runtime version '" + version + "' not found; registered: " + listVersions());
    }

    /** Каталог проверочной выгрузки — в каталоге tmp домашнего каталога сервера, а не в системном temp. */
    private static Path newTempDirectory() throws ToolException {
        try {
            Path dir = McpHome.root().resolve("tmp");
            Files.createDirectories(dir);
            return Files.createTempDirectory(dir, "1cv8-probe-");
        } catch (IOException | RuntimeException e) {
            throw new ToolException("не удалось создать каталог проверки новой базы: " + e.getMessage(), e);
        }
    }

    /** Удаляет временный каталог вместе с содержимым; не удалилось — не беда. */
    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // временный каталог; остаток уберёт следующая чистка tmp
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // то же
        }
    }

    /**
     * {@code DBPwd=…}/{@code SPwd=…}: значение в кавычках целиком (каноническая форма строки
     * подключения 1С — {@code DBPwd="…"}) или без кавычек — до первого {@code ;}, пробела или кавычки.
     * Проверка аргументов ({@code ServerInfobaseParams.checkChars}) касается входа, а не того, как
     * 1cv8 эхом повторяет строку в тексте отказа.
     */
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(DBPwd|SPwd)\\s*=\\s*(\"[^\"]*\"|[^;\\s\"]*)");

    /**
     * Буквальные вхождения пароля вырезаются, только если в нём 4 символа и больше: пароль в 1–3
     * символа совпал бы с кусками обычного текста и изуродовал его. Такой пароль закрывает
     * шаблон «ключ=значение».
     */
    private static final int MIN_LITERAL_SECRET_LENGTH = 4;

    /**
     * Вырезает пароли СУБД/кластера из текста {@code /Out}/stderr перед тем, как он попадёт в
     * сообщение: сначала форма «ключ=значение», затем — буквальные вхождения самих паролей (1cv8
     * может повторить значение и вне строки подключения). Самый длинный пароль — первым: если один
     * пароль — часть другого, короткий, вырезанный раньше, оставил бы в тексте хвост длинного.
     */
    private static String maskSecrets(String text, List<String> secrets) {
        if (text == null || text.isEmpty()) return text;
        String masked = SECRET_PATTERN.matcher(text).replaceAll("$1=***");
        List<String> longestFirst = new ArrayList<>();
        for (String secret : secrets) {
            if (secret != null && secret.length() >= MIN_LITERAL_SECRET_LENGTH) longestFirst.add(secret);
        }
        longestFirst.sort(Comparator.comparingInt(String::length).reversed());
        for (String secret : longestFirst) {
            masked = masked.replace(secret, "***");
        }
        return masked;
    }

    /** Resolves 1cv8 for the platform version of the given infobase. */
    public String resolveExecutableForInfobase(InfobaseReference ref) {
        String version = ref.getVersion();
        if (version == null || version.isBlank()) version = ref.getDefaultVersion();
        if (version == null || version.isBlank()) return null;
        try {
            return resolveExecutable(version);
        } catch (ToolException e) {
            return null;
        }
    }

    private String resolveExecutable(String version) throws ToolException {
        File executable = executableResolver.resolve(version);
        return executable.getAbsolutePath();
    }

    private String listVersions() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (IRuntime r : registry.getRuntimes()) {
            if (!first) sb.append(", ");
            sb.append(r.getVersion());
            first = false;
        }
        return sb.append("]").toString();
    }

    /**
     * Production resolver: walks {@code IResolvableRuntimeInstallationManager → RuntimeInstallation
     * → IRuntimeComponentManager.resolveExecutor → IThickClientLauncher → ILaunchableRuntimeComponent.getFile()}.
     *
     * <p>Same chain as {@code ClientLauncher.resolveThickClientExecutable(Version)} in
     * {@code ru.fedukhin.edt.mcp.tools.client.internal}; duplicated rather than cross-bundle imported
     * because the two seams have different injection scopes. Keep {@code RUNTIME_TYPE_ID} in sync
     * with {@code ClientLauncher.RUNTIME_TYPE_ID} — both are validated only at Stage 3b Task 12 manual smoke.
     */
    public static class DefaultExecutableResolver implements ExecutableResolver {

        /**
         * Runtime type id passed to {@link IResolvableRuntimeInstallationManager#resolveByFullVersion}.
         * Verified via constant pool of {@code ResolvableRuntimeInstallationManager}:
         * the only two registered runtime types are {@code runtimeType.EnterprisePlatform}
         * (desktop 1С) and {@code runtimeType.MobilePlatform} (mobile). We use
         * EnterprisePlatform for thick-client / CREATEINFOBASE.
         * Same value as {@code ClientLauncher.RUNTIME_TYPE_ID} — keep in sync.
         */
        public static final String RUNTIME_TYPE_ID =
            "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform";

        private final IResolvableRuntimeInstallationManager installationManager;
        private final IRuntimeComponentManager componentManager;

        public DefaultExecutableResolver(IResolvableRuntimeInstallationManager im,
                                          IRuntimeComponentManager cm) {
            this.installationManager = im;
            this.componentManager = cm;
        }

        @Override public File resolve(String version) throws ToolException {
            // archs argument is a list of REQUIRED component type ids; AppArch.AUTO
            // matches whatever is installed. Same pattern as EDT RuntimeClientLaunchDelegate.
            java.util.List<String> requiredComponents =
                java.util.List.of(IRuntimeComponentTypes.THICK_CLIENT);

            // Pick the LATEST installed patch matching the requested version prefix.
            // For "8.3.27" with both .1688 and .2130 installed, prefer .2130 — EDT's
            // own resolveByFullVersion does not guarantee latest, which clashes with
            // EDT-held sessions on a FILE infobase that pin a specific patch.
            IResolvableRuntimeInstallation resolvable = pickLatestMatching(version);
            if (resolvable == null) {
                try {
                    resolvable = installationManager.resolveByFullVersion(
                        RUNTIME_TYPE_ID, version, requiredComponents, AppArch.AUTO);
                } catch (MatchingRuntimeNotFound e) {
                    throw new ToolException("no thick-client installation for version '" + version + "': "
                        + e.getMessage(), e);
                }
            }
            RuntimeInstallation install;
            try {
                install = resolvable.resolve(requiredComponents, AppArch.AUTO);
            } catch (MatchingRuntimeNotFound e) {
                throw new ToolException("failed to resolve installation for version '" + version + "'", e);
            }
            ComponentExecutorInfo<ILaunchableRuntimeComponent, IThickClientLauncher> info;
            try {
                info = componentManager.resolveExecutor(
                    ILaunchableRuntimeComponent.class, IThickClientLauncher.class,
                    install, IRuntimeComponentTypes.THICK_CLIENT);
            } catch (RuntimeExecutionException e) {
                throw new ToolException("failed to resolve thick-client executor: " + e.getMessage(), e);
            }
            return info.getComponent().getFile();
        }

        /**
         * Returns the installation with the highest version mask matching
         * {@code version} as a prefix (e.g., "8.3.27" matches "8.3.27.1688" and
         * "8.3.27.2130" — the latter wins via {@link IResolvableRuntimeInstallation}'s
         * natural Comparable ordering). Returns {@code null} if no installation
         * matches or if the manager API call throws.
         */
        private IResolvableRuntimeInstallation pickLatestMatching(String version) {
            try {
                IResolvableRuntimeInstallation best = null;
                for (IResolvableRuntimeInstallation r : installationManager.getAll(RUNTIME_TYPE_ID)) {
                    String mask = r.getVersionMask();
                    if (mask == null) continue;
                    if (!mask.equals(version) && !mask.startsWith(version + ".")) continue;
                    if (best == null || best.compareTo(r) < 0) best = r;
                }
                return best;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    static class DefaultProcessFactory implements ProcessFactory {
        @Override public Process start(List<String> command, File workingDir) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) pb.directory(workingDir);
            return pb.start();
        }
    }
}
