package ru.fedukhin.edt.mcp.tools.client.internal;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThinClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.version.Version;
import jakarta.inject.Inject;
import java.io.File;
import java.net.URL;
import java.util.List;
import org.eclipse.core.runtime.CoreException;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Resolves the right thin / thick client launcher and starts a {@link Process}
 * via {@link IRuntimeClientLauncher#startClientByInfobaseReference}.
 *
 * <p>Also exposes {@link #resolveThickClientExecutable(Version)} so that
 * {@code RuntimeCli.DefaultExecutableResolver} (in {@code tools.infobase}) can
 * close the Stage-3 SPIKE without duplicating the resolve chain — the duplication
 * itself is necessary because the two seams have different injection scopes.
 */
public class ClientLauncher {

    /**
     * Runtime type id passed to {@link IResolvableRuntimeInstallationManager#resolveByVersionAndInfobase}.
     * Verified via constant pool of {@code ResolvableRuntimeInstallationManager}:
     * the only two registered runtime types are {@code runtimeType.EnterprisePlatform}
     * (desktop 1С) and {@code runtimeType.MobilePlatform} (mobile). We use
     * EnterprisePlatform for thin/thick clients.
     * Keep in sync with {@code RuntimeCli.DefaultExecutableResolver.RUNTIME_TYPE_ID}.
     */
    public static final String RUNTIME_TYPE_ID =
        "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform";

    private final IResolvableRuntimeInstallationManager installationManager;
    private final IRuntimeComponentManager componentManager;
    private final IInfobaseAccessManager accessManager;

    @Inject
    public ClientLauncher(IResolvableRuntimeInstallationManager im, IRuntimeComponentManager cm,
                          IInfobaseAccessManager accessManager) {
        this.installationManager = im;
        this.componentManager = cm;
        this.accessManager = accessManager;
    }

    /** Test seam — без stored-creds lookup. */
    public ClientLauncher(IResolvableRuntimeInstallationManager im, IRuntimeComponentManager cm) {
        this(im, cm, null);
    }

    public Process launch(InfobaseReference infobase, String clientType,
                          String user, String password) throws ToolException {
        RuntimeExecutionArguments args = new RuntimeExecutionArguments();
        applyCredentials(args, user, password, infobase);
        return doLaunch(infobase, clientType, args);
    }

    /**
     * Like {@link #launch}, but the client is launched <b>attached</b> to an already-running
     * debug server: {@code debugServerUrl} is set on the execution arguments, which makes
     * EDT append {@code /DEBUG -http -attach /DEBUGGERURL <url>} to the client command line.
     * Used by {@code tools.debug}'s {@code DebugLauncher} (Stage 3c Plan 3).
     */
    public Process launchForDebug(InfobaseReference infobase, String clientType,
                                  String user, String password, URL debugServerUrl)
            throws ToolException {
        RuntimeExecutionArguments args = new RuntimeExecutionArguments();
        applyCredentials(args, user, password, infobase);
        args.setDebugServerUrl(debugServerUrl);
        return doLaunch(infobase, clientType, args);
    }

    /**
     * Активирует путь user/password в EDT. Без {@code setAccess(...)}
     * {@code AbstractRuntimeComponentExecutor.appendInfobaseAccess} ранний return —
     * creds в cmdline НЕ попадают, и платформа показывает логин-диалог.
     * Подтверждено decomp'ом ThinClientLauncher + WMIC дампом (task #18).
     *
     * <p>Если caller не передал user/password — пробуем достать сохранённые EDT'ом
     * настройки через {@link IInfobaseAccessManager#resolveSettings} (Eclipse
     * SecurePreferences). UI-диалог отладчика 1С таким образом тоже их читает.
     */
    private void applyCredentials(RuntimeExecutionArguments args, String user, String password,
                                  InfobaseReference infobase) {
        boolean hasUser = user != null && !user.isEmpty();
        boolean hasPwd = password != null;
        if (hasUser) args.setUsername(user);
        if (hasPwd) args.setPassword(password);
        if (hasUser || hasPwd) {
            args.setAccess(InfobaseAccess.INFOBASE);
            return;
        }
        if (accessManager == null) {
            return;   // test-seam: пропускаем lookup
        }
        // Fallback на stored EDT-настройки.
        IInfobaseAccessSettings stored;
        try {
            stored = accessManager.resolveSettings(infobase);
        } catch (CoreException e) {
            return;
        }
        if (stored == null || stored == IInfobaseAccessSettings.NOT_DEFINED) {
            return;
        }
        InfobaseAccess access = stored.access();
        if (access == null) {
            return;
        }
        args.setAccess(access);
        if (access == InfobaseAccess.INFOBASE) {
            if (stored.userName() != null) args.setUsername(stored.userName());
            if (stored.password() != null) args.setPassword(stored.password());
        }
    }

    private Process doLaunch(InfobaseReference infobase, String clientType,
                             RuntimeExecutionArguments args) throws ToolException {
        Version version = parseVersion(infobase.getVersion());
        String componentTypeId;
        Class<? extends IRuntimeClientLauncher> executorClass;
        switch (clientType) {
            case "thin":
                componentTypeId = IRuntimeComponentTypes.THIN_CLIENT;
                executorClass = IThinClientLauncher.class;
                break;
            case "thick":
                componentTypeId = IRuntimeComponentTypes.THICK_CLIENT;
                executorClass = IThickClientLauncher.class;
                break;
            default:
                throw new ToolException("unknown client type: " + clientType);
        }

        // archs argument here is actually a list of REQUIRED component type ids
        // (verified empirically against EDT RuntimeClientLaunchDelegate which calls
        //  Collections.singletonList(componentTypeId) + AppArch.AUTO).
        List<String> requiredComponents = List.of(componentTypeId);
        IResolvableRuntimeInstallation resolvable;
        try {
            resolvable = installationManager.resolveByVersionAndInfobase(
                RUNTIME_TYPE_ID, version, infobase,
                InfobaseAccessType.CLIENT_LAUNCH, requiredComponents);
        } catch (MatchingRuntimeNotFound e) {
            throw new ToolException("no '" + clientType + "' installation for infobase '"
                + infobase.getName() + "' v" + infobase.getVersion() + ": " + e.getMessage(), e);
        }
        RuntimeInstallation install;
        try {
            install = resolvable.resolve(requiredComponents, AppArch.AUTO);
        } catch (MatchingRuntimeNotFound e) {
            throw new ToolException("failed to resolve installation for '"
                + infobase.getName() + "': " + e.getMessage(), e);
        }
        ComponentExecutorInfo<ILaunchableRuntimeComponent, ? extends IRuntimeClientLauncher> info;
        try {
            info = componentManager.resolveExecutor(
                ILaunchableRuntimeComponent.class, executorClass, install, componentTypeId);
        } catch (RuntimeExecutionException e) {
            throw new ToolException("failed to resolve client executor: " + e.getMessage(), e);
        }
        try {
            return info.getExecutor().startClientByInfobaseReference(info.getComponent(), infobase, args);
        } catch (RuntimeExecutionException e) {
            throw new ToolException("client launch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the on-disk {@code 1cv8.exe} {@link File} for the thick client of the given version.
     * Used by {@code RuntimeCli.DefaultExecutableResolver} (Stage 3 SPIKE).
     */
    public File resolveThickClientExecutable(Version version) throws ToolException {
        List<String> requiredComponents = List.of(IRuntimeComponentTypes.THICK_CLIENT);
        IResolvableRuntimeInstallation resolvable;
        try {
            resolvable = installationManager.resolveByFullVersion(
                RUNTIME_TYPE_ID, version.toString(), requiredComponents, AppArch.AUTO);
        } catch (MatchingRuntimeNotFound e) {
            throw new ToolException("no thick-client installation for version '" + version + "': "
                + e.getMessage(), e);
        }
        RuntimeInstallation install;
        try {
            install = resolvable.resolve(requiredComponents, AppArch.AUTO);
        } catch (MatchingRuntimeNotFound e) {
            throw new ToolException("failed to resolve installation for version '" + version
                + "': " + e.getMessage(), e);
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

    private static Version parseVersion(String s) throws ToolException {
        if (s == null || s.isEmpty()) {
            throw new ToolException("infobase has no platform version set");
        }
        try {
            return Version.parseVersion(s);
        } catch (RuntimeException e) {
            throw new ToolException("invalid infobase version: '" + s + "'", e);
        }
    }
}
