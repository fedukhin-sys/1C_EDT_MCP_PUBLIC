package ru.fedukhin.edt.mcp.tools.testrun.di;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.ModuleFileBootstrap;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;
import ru.fedukhin.edt.mcp.tools.testrun.InstallTestRunnerTool;
import ru.fedukhin.edt.mcp.tools.testrun.RunTestMethodTool;
import ru.fedukhin.edt.mcp.tools.testrun.RunTestsTool;
import ru.fedukhin.edt.mcp.tools.testrun.UninstallTestRunnerTool;
import ru.fedukhin.edt.mcp.tools.testrun.internal.IdeManagedAppModuleEditor;
import ru.fedukhin.edt.mcp.tools.testrun.internal.IdeModuleScaffolder;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller.ManagedAppModuleEditor;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller.ModuleScaffolder;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher.ProcessRunner;

public final class ToolsTestrunModule extends AbstractServiceAwareModule {

    public ToolsTestrunModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        // Public EDT services
        bind(IV8ProjectManager.class).toService();
        bind(IInfobaseManager.class).toService();
        bind(IBmModelManager.class).toService();
        bind(IConfigurationProvider.class).toService();

        // OSGi services required transitively by RuntimeCli.
        // Each Guice injector is per-bundle, so we must re-bind these here even
        // though tools.infobase already does the same — otherwise this bundle's
        // injector fails with MissingImplementation for RuntimeCli's deps.
        bind(IRuntimeRegistry.class).toService();
        bind(IResolvableRuntimeInstallationManager.class).toService();
        bind(IRuntimeComponentManager.class).toService();

        // Reuse from tools.infobase
        bind(InfobaseRegistry.class).in(Singleton.class);
        bind(RuntimeCli.class).in(Singleton.class);

        // tools.md.internal helpers (needed by IdeModuleScaffolder)
        bind(MdObjectRegistry.class).in(Singleton.class);
        bind(BmPersistentExecutor.class).in(Singleton.class);
        bind(MdObjectFactory.class).in(Singleton.class);
        bind(ModuleFileBootstrap.class).in(Singleton.class);
        bind(PropertyAccessor.class).in(Singleton.class);
        bind(MdObjectLocator.class).in(Singleton.class);

        // Internal singletons
        bind(TestRunnerInstaller.class).in(Singleton.class);
        bind(TestRunnerLauncher.class).in(Singleton.class);

        // ProcessRunner default impl: real ProcessBuilder-based runner.
        bind(ProcessRunner.class).toInstance(new DefaultProcessRunner());

        // Real scaffolder + editor implementations (Task 9).
        bind(ModuleScaffolder.class).to(IdeModuleScaffolder.class).in(Singleton.class);
        bind(ManagedAppModuleEditor.class).to(IdeManagedAppModuleEditor.class).in(Singleton.class);

        // Tool bindings
        bind(InstallTestRunnerTool.class);
        bind(UninstallTestRunnerTool.class);
        bind(RunTestsTool.class);
        bind(RunTestMethodTool.class);
    }

    /** Default ProcessRunner — launches via ProcessBuilder, captures stdout/stderr. */
    static final class DefaultProcessRunner implements ProcessRunner {
        @Override public RunOutcome run(List<String> command, Map<String, String> env,
                                         int timeoutSeconds) throws Exception {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (env != null && !env.isEmpty()) pb.environment().putAll(env);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String stdout = readAll(p.getInputStream());
            String stderr = readAll(p.getErrorStream());
            int exit = p.waitFor();
            return new RunOutcome(exit, stdout, stderr);
        }
        private static String readAll(java.io.InputStream in) throws IOException {
            byte[] data = in.readAllBytes();
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
