package ru.fedukhin.edt.mcp.tools.testrun.internal;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.core.resources.IProject;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Orchestrates xUnit test runner scaffolding into a project: creates two
 * CommonModules (client + server) and appends a ПриНачалеРаботыСистемы handler
 * to src/Configuration/ManagedApplicationModule.bsl. Real I/O is delegated to
 * the {@link ModuleScaffolder} and {@link ManagedAppModuleEditor} seam
 * interfaces so the orchestration logic is unit-testable without an IDE.
 */
@Singleton
public class TestRunnerInstaller {

    public static final String CLIENT_MODULE = "EDT_MCP_TestRunner_Клиент";
    public static final String SERVER_MODULE = "EDT_MCP_TestRunner_Сервер";
    public static final String MARKER_BEGIN = "// === EDT_MCP_TestRunner BEGIN ===";
    public static final String MARKER_END   = "// === EDT_MCP_TestRunner END ===";

    /** Creates / deletes CommonModule artifacts. Real impl in Task 9. */
    public interface ModuleScaffolder {
        boolean exists(IProject project, String fqn);
        /**
         * Task #10: detects BM-side zombies (MdObject в BM при отсутствующем
         * disk-folder). Используется uninstall'ом для cleanup'а partial installs.
         * Default = exists() — переопределяется в реальной impl.
         */
        default boolean existsInBm(IProject project, String fqn) { return exists(project, fqn); }
        void createClientModule(IProject project, String name) throws ToolException;
        void createServerModule(IProject project, String name) throws ToolException;
        void deleteModule(IProject project, String fqn) throws ToolException;
    }

    /** Edits ManagedApplicationModule.bsl text. Real impl in Task 9. */
    public interface ManagedAppModuleEditor {
        boolean hasMarker(IProject project);
        void appendConfigurationHandler(IProject project) throws ToolException;
        void appendExtensionHandler(IProject project) throws ToolException;
        void removeMarkerBlock(IProject project) throws ToolException;
    }

    public record InstallResult(String mode, boolean alreadyInstalled,
                                 String clientModule, String serverModule, String handlerLocation,
                                 boolean warningInvasive) { }

    private final ModuleScaffolder scaffolder;
    private final ManagedAppModuleEditor editor;

    @Inject
    public TestRunnerInstaller(ModuleScaffolder scaffolder, ManagedAppModuleEditor editor) {
        this.scaffolder = scaffolder;
        this.editor = editor;
    }

    public InstallResult install(IV8Project project) throws ToolException {
        String mode = detectMode(project);
        IProject p = project.getProject();
        boolean modulesPresent = scaffolder.exists(p, "CommonModule." + CLIENT_MODULE)
                              && scaffolder.exists(p, "CommonModule." + SERVER_MODULE);
        boolean handlerPresent = editor.hasMarker(p);
        boolean alreadyInstalled = modulesPresent && handlerPresent;
        if (alreadyInstalled) {
            return new InstallResult(mode, true, CLIENT_MODULE, SERVER_MODULE,
                handlerLocationFor(mode), "configuration".equals(mode));
        }
        if (!scaffolder.exists(p, "CommonModule." + CLIENT_MODULE)) {
            scaffolder.createClientModule(p, CLIENT_MODULE);
        }
        if (!scaffolder.exists(p, "CommonModule." + SERVER_MODULE)) {
            scaffolder.createServerModule(p, SERVER_MODULE);
        }
        if (!handlerPresent) {
            if ("extension".equals(mode)) {
                editor.appendExtensionHandler(p);
            } else {
                editor.appendConfigurationHandler(p);
            }
        }
        return new InstallResult(mode, false, CLIENT_MODULE, SERVER_MODULE,
            handlerLocationFor(mode), "configuration".equals(mode));
    }

    public boolean uninstall(IV8Project project) throws ToolException {
        IProject p = project.getProject();
        // Task #10: existsInBm() catches BM-zombies (MdObject без disk-folder),
        // которые остались от partial install'ов до Fix B.
        boolean clientPresent  = scaffolder.exists(p,      "CommonModule." + CLIENT_MODULE)
                              || scaffolder.existsInBm(p, "CommonModule." + CLIENT_MODULE);
        boolean serverPresent  = scaffolder.exists(p,      "CommonModule." + SERVER_MODULE)
                              || scaffolder.existsInBm(p, "CommonModule." + SERVER_MODULE);
        boolean handlerPresent = editor.hasMarker(p);
        if (!clientPresent && !serverPresent && !handlerPresent) return false;
        if (clientPresent) {
            scaffolder.deleteModule(p, "CommonModule." + CLIENT_MODULE);
        }
        if (serverPresent) {
            scaffolder.deleteModule(p, "CommonModule." + SERVER_MODULE);
        }
        if (handlerPresent) {
            editor.removeMarkerBlock(p);
        }
        return true;
    }

    private static String detectMode(IV8Project project) throws ToolException {
        if (project instanceof IExtensionProject) return "extension";
        if (project instanceof IConfigurationProject) return "configuration";
        throw new ToolException("project '" + project.getProject().getName()
            + "' is not a Configuration or Extension project");
    }

    private static String handlerLocationFor(String mode) {
        return "extension".equals(mode)
            ? "ManagedApplicationModule.bsl (extension &После annotation)"
            : "ManagedApplicationModule.bsl (configuration ПриНачалеРаботыСистемы)";
    }
}
