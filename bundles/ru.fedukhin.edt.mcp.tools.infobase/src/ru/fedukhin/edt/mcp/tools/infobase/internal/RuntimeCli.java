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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import ru.fedukhin.edt.mcp.core.api.ToolException;

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
    }

    /**
     * Run {@code 1cv8.exe CREATEINFOBASE File="<location>"} synchronously.
     * Returns process exit code (always 0 on success — non-zero throws).
     */
    public int createFileInfobase(Path location, String version, Duration timeout) throws ToolException {
        // IRuntimeRegistry.getRuntime(String) treats its argument as a runtime *id*
        // (e.g. "com._1c.g5.v8.dt.platform.runtime.8.3.27"), NOT a version. Search
        // by version-string via getRuntimes() to give a clean "not found" diagnostic
        // before delegating to the resolver chain (which throws MatchingRuntimeNotFound
        // with its own less actionable message).
        boolean known = false;
        for (IRuntime r : registry.getRuntimes()) {
            if (version.equals(String.valueOf(r.getVersion()))) {
                known = true;
                break;
            }
        }
        if (!known) {
            throw new ToolException("runtime version '" + version + "' not found; registered: " + listVersions());
        }
        File executable = executableResolver.resolve(version);

        // Build the command line directly — the command-builder facade lives in services.core.runtimes.execution.impl
        // and requires a fully-resolved RuntimeInstallation, which we don't have in the headless path.
        // Equivalent CLI shape (1C platform docs): 1cv8.exe CREATEINFOBASE File=<path>
        //
        // Без кавычек вокруг пути: ProcessBuilder на Windows экранирует встроенные " как \",
        // а 1cv8.exe такую форму не понимает — отвечает «Неопределена информационная база»
        // (тот же отказ описан в TestRunnerLauncher.buildCommand). Кавычки вокруг аргумента
        // с пробелами Windows расставляет сам.
        List<String> cmd = List.of(executable.getAbsolutePath(), "CREATEINFOBASE", "File=" + location);

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
        Thread errDrain = drainAsync(p.getErrorStream(), stderr);
        Thread outDrain = drainAsync(p.getInputStream(), new StringBuilder());
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
        joinQuietly(errDrain);
        joinQuietly(outDrain);
        int code = p.exitValue();
        if (code != 0) {
            throw new ToolException("1cv8 exited " + code + ": " + stderr.toString().trim());
        }
        return code;
    }

    /** Вычитывает поток процесса в daemon-потоке, чтобы не блокировать ожидание. */
    private static Thread drainAsync(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            String read = tail(in);
            synchronized (sink) { sink.append(read); }
        }, "edt-mcp-1cv8-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private static String tail(InputStream in) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1 && baos.size() < 4096) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "<failed to read stderr: " + e.getMessage() + ">";
        }
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
