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

    /**
     * Имена до введения суффикса по проекту. Оставлены ради миграции: установка
     * сносит их, а run_tests продолжает признавать уже задеплоенные раннеры.
     */
    public static final String CLIENT_MODULE = "EDT_MCP_TestRunner_Клиент";
    public static final String SERVER_MODULE = "EDT_MCP_TestRunner_Сервер";

    /** Предел длины идентификатора общего модуля 1С. */
    private static final int MAX_1C_IDENTIFIER = 80;
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

    /**
     * Имя клиентского модуля раннера для проекта.
     *
     * <p>Суффикс нужен потому, что уникальность требуется на уровне ИНФОРМАЦИОННОЙ
     * БАЗЫ, а не проекта: два расширения одной базы с одинаковыми именами общих
     * модулей приводят к тому, что 1С молча отключает второе расширение целиком,
     * и видно это только в журнале регистрации.
     */
    public static String clientModule(String projectName) {
        return named(CLIENT_MODULE, projectName);
    }

    public static String serverModule(String projectName) {
        return named(SERVER_MODULE, projectName);
    }

    /** Идентификатор 1С: только буквы, цифры и подчёркивание, не длиннее 80 символов. */
    private static String named(String base, String projectName) {
        if (projectName == null || projectName.isBlank()) return base;
        StringBuilder sb = new StringBuilder();
        for (char c : projectName.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        String full = base + "_" + sb;
        return full.length() <= MAX_1C_IDENTIFIER ? full : full.substring(0, MAX_1C_IDENTIFIER);
    }

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
        String client = clientModule(p.getName());
        String server = serverModule(p.getName());

        boolean modulesPresent = scaffolder.exists(p, "CommonModule." + client)
                              && scaffolder.exists(p, "CommonModule." + server);
        boolean handlerPresent = editor.hasMarker(p);
        boolean alreadyInstalled = modulesPresent && handlerPresent;
        if (alreadyInstalled) {
            return new InstallResult(mode, true, client, server,
                handlerLocationFor(mode), "configuration".equals(mode));
        }
        // Миграция: модули без суффикса конфликтовали бы по имени со вторым
        // расширением той же информационной базы, поэтому сносим их.
        removeLegacyModules(p);
        if (!scaffolder.exists(p, "CommonModule." + client)) {
            scaffolder.createClientModule(p, client);
        }
        if (!scaffolder.exists(p, "CommonModule." + server)) {
            scaffolder.createServerModule(p, server);
        }
        if (!handlerPresent) {
            if ("extension".equals(mode)) {
                editor.appendExtensionHandler(p);
            } else {
                editor.appendConfigurationHandler(p);
            }
        }
        return new InstallResult(mode, false, client, server,
            handlerLocationFor(mode), "configuration".equals(mode));
    }

    /** Сносит модули старого образца (без суффикса), если они остались от прошлой установки. */
    private void removeLegacyModules(IProject p) throws ToolException {
        for (String legacy : new String[] { CLIENT_MODULE, SERVER_MODULE }) {
            String fqn = "CommonModule." + legacy;
            if (scaffolder.exists(p, fqn) || scaffolder.existsInBm(p, fqn)) {
                scaffolder.deleteModule(p, fqn);
            }
        }
    }

    public boolean uninstall(IV8Project project) throws ToolException {
        IProject p = project.getProject();
        // Task #10: existsInBm() catches BM-zombies (MdObject без disk-folder),
        // которые остались от partial install'ов до Fix B.
        // Снимаем оба образца имён: у пользователя может стоять раннер до миграции.
        String[] candidates = {
            clientModule(p.getName()), serverModule(p.getName()), CLIENT_MODULE, SERVER_MODULE
        };
        boolean anyModule = false;
        for (String name : candidates) {
            String fqn = "CommonModule." + name;
            if (scaffolder.exists(p, fqn) || scaffolder.existsInBm(p, fqn)) {
                anyModule = true;
            }
        }
        boolean handlerPresent = editor.hasMarker(p);
        if (!anyModule && !handlerPresent) return false;
        for (String name : candidates) {
            String fqn = "CommonModule." + name;
            if (scaffolder.exists(p, fqn) || scaffolder.existsInBm(p, fqn)) {
                scaffolder.deleteModule(p, fqn);
            }
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
