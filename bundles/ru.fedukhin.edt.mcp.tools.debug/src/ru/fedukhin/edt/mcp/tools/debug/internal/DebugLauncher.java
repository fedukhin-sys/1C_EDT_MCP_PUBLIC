package ru.fedukhin.edt.mcp.tools.debug.internal;

import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTargetManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.version.Version;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.util.List;
import java.util.UUID;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.Launch;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientLauncher;
import ru.fedukhin.edt.mcp.tools.client.internal.InfobaseLookup;

/**
 * Orchestrates the {@code debug_client} two-step choreography confirmed by the Plan-2 spike
 * (see {@code 2026-05-14-spike-stage-3c-debug.md} "Plan 3 inputs" Step 1):
 *
 * <ol>
 *   <li>Resolve the infobase, build a {@link Launch} on top of a minimal {@code LocalRuntime}
 *       working-copy config (carrying {@code ATTR_PROJECT_NAME}; required by {@code createLocal}'s
 *       {@code getConfigurationScriptVariant}) with a {@code null} source-locator (the locator
 *       nullity is what dodges the {@code xtext.ui} wall — spike risk #1). Resolve a DebugServer
 *       {@link RuntimeInstallation} and call {@link IRuntimeDebugClientTargetManager#createLocal}
 *       — EDT picks a free port, runs the {@code dbgs} server process, and returns an
 *       already-connected target.</li>
 *   <li>Launch the 1С client <b>attached</b> to {@code target.getDebugServerUrl()} via
 *       {@link ClientLauncher#launchForDebug}.</li>
 * </ol>
 *
 * <p>Mock-unit-tested only — the full path needs a live 1С runtime, so it is exercised by the
 * manual IDE smoke test (spec §10.5), the established pattern for Stages 1 / 3 / 3b.
 */
public class DebugLauncher {

    /** Spike-confirmed launch-config type for launch-in-debug (spike "Plan 3 inputs" Step 3). */
    private static final String LAUNCH_CONFIG_TYPE_ID = "com._1c.g5.v8.dt.debug.core.LocalRuntime";
    /** Attribute key read by {@code createLocal}'s {@code getConfigurationScriptVariant(launch)}. */
    private static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME";

    private final InfobaseLookup infobaseLookup;
    private final IResolvableRuntimeInstallationManager installationManager;
    private final IRuntimeDebugClientTargetManager debugTargetManager;
    private final ClientLauncher clientLauncher;

    @Inject
    public DebugLauncher(InfobaseLookup infobaseLookup,
                         IResolvableRuntimeInstallationManager installationManager,
                         IRuntimeDebugClientTargetManager debugTargetManager,
                         ClientLauncher clientLauncher) {
        this.infobaseLookup = infobaseLookup;
        this.installationManager = installationManager;
        this.debugTargetManager = debugTargetManager;
        this.clientLauncher = clientLauncher;
    }

    /** Launch {@code clientType} ("thin"/"thick") against {@code infobaseName} under the debugger. */
    public DebugLaunchResult launch(String infobaseName, String clientType,
                                    String user, String password) throws ToolException {
        InfobaseReference infobase = infobaseLookup.findByName(infobaseName)
                .orElseThrow(() -> new ToolException("infobase '" + infobaseName + "' not found"));

        // ILaunch with a minimal LocalRuntime working-copy config (carrying ATTR_PROJECT_NAME)
        // and a null source locator. The null locator dodges sourceLocatorId -> xtext.ui ->
        // ui.workbench (spike risk #1). The non-null config is required: createLocal internally
        // calls getConfigurationScriptVariant(launch) which reads ATTR_PROJECT_NAME from
        // launch.getLaunchConfiguration() — with a null config it surfaces (on this EDT 2026.1
        // build) as "Не найден свободный порт на сервере отладки" (the live IDE smoke on
        // 2026-05-15 reproduced exactly that; manual EDT Debug Configuration on the same
        // machine, same minute, worked because it built a full LocalRuntime working-copy).
        // This is the spike's [best-inference-needs-smoke] fix (notes "Plan 3 inputs" Step 1).
        ILaunch launch = buildLaunch(infobase.getName());

        RuntimeInstallation installation = resolveDebugServerInstallation(infobase);

        // Pick a free port ourselves and pass it explicitly. EDT 2026.1's
        // RuntimeDebugClientTargetManager.runDebugServerAndCreateDebugTarget(...) has a bytecode
        // bug (verified via the diag patch #4 + javap of the EDT-debug jar): when port == -1 is
        // passed it calls HttpUtil.findFreePort() into a LOCAL var but the SECOND port-check
        // still reads the (unchanged) parameter, so it always throws CoreException(
        // "Не найден свободный порт на сервере отладки"). Passing any non-(-1) port skips the
        // buggy branch (iload_3 != -1 → jump-to-152 path in the bytecode). EDT UI debug doesn't
        // hit this because its launch config sets ATTR_DEBUG_SERVER_PORT to an explicit port.
        int port = findFreePort();
        IRuntimeDebugClientTarget target;
        try {
            // EDT then runs dbgs.exe on `port`, wraps it in an IProcess, and connect()s.
            target = debugTargetManager.createLocal(installation, infobase, port, launch);
        } catch (Exception e) {
            // DIAGNOSTIC (temporary): dump the full EDT-internal stack to .metadata/.log so we can
            // see which method actually threw — the surface message ("Не найден свободный порт на
            // сервере отладки") is a misleading byte-string from dbgs.exe / wrapper and the real
            // call chain is what matters for the next fix iteration. The Platform.getLog(...)
            // entry shows up in workspace `.metadata/.log` with severity ERROR.
            Platform.getLog(FrameworkUtil.getBundle(DebugLauncher.class))
                    .log(new Status(IStatus.ERROR, "ru.fedukhin.edt.mcp.tools.debug",
                            "[diag] debug_client createLocal failed for infobase '"
                                    + infobase.getName() + "' (v" + infobase.getVersion() + ", "
                                    + "launch.config=" + (launch.getLaunchConfiguration() == null
                                            ? "null"
                                            : launch.getLaunchConfiguration().getName())
                                    + "): " + e.getClass().getName() + ": " + e.getMessage(),
                            e));
            throw new ToolException("debug launch failed: " + e.getMessage(), e);
        }

        URL debugServerUrl;
        try {
            debugServerUrl = new URL(target.getDebugServerUrl());
        } catch (MalformedURLException e) {
            throw new ToolException("debug launch failed: bad debug server url '"
                    + target.getDebugServerUrl() + "'", e);
        }

        Process clientProcess =
                clientLauncher.launchForDebug(infobase, clientType, user, password, debugServerUrl);

        return new DebugLaunchResult(target, launch, clientProcess, infobase.getName(), clientType);
    }

    private RuntimeInstallation resolveDebugServerInstallation(InfobaseReference infobase)
            throws ToolException {
        Version version = parseVersion(infobase.getVersion());
        List<String> required = List.of(IRuntimeComponentTypes.DEBUG_SERVER);
        IResolvableRuntimeInstallation resolvable;
        try {
            resolvable = installationManager.resolveByVersionAndInfobase(
                    ClientLauncher.RUNTIME_TYPE_ID, version, infobase,
                    InfobaseAccessType.CLIENT_LAUNCH, required);
        } catch (MatchingRuntimeNotFound e) {
            throw new ToolException("no debug-server installation for infobase '"
                    + infobase.getName() + "' v" + infobase.getVersion() + ": " + e.getMessage(), e);
        }
        try {
            return resolvable.resolve(required, AppArch.AUTO);
        } catch (MatchingRuntimeNotFound e) {
            throw new ToolException("failed to resolve debug-server installation for '"
                    + infobase.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Build the {@link ILaunch} for {@link IRuntimeDebugClientTargetManager#createLocal}: a
     * working-copy {@code LocalRuntime} config carrying {@code ATTR_PROJECT_NAME}, wrapped in a
     * {@link Launch} with a {@code null} source-locator. The source-locator nullity is what
     * dodges the {@code xtext.ui} wall (spike risk #1); the config presence is what keeps
     * {@code getConfigurationScriptVariant(launch)} happy (spike "Plan 3 inputs" Step 1).
     *
     * <p>The project-name attribute is filled with {@code projectName} (the caller passes
     * {@code infobase.getName()} — for the common case of an infobase with the same name as
     * its associated EDT project that resolves; for other infobases any non-null value gets
     * {@code getConfigurationScriptVariant} past the NPE, the live debug-server start then runs
     * off the resolved DebugServer installation, not off this attribute).
     */
    private static ILaunch buildLaunch(String projectName) throws ToolException {
        ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(LAUNCH_CONFIG_TYPE_ID);
        if (type == null) {
            throw new ToolException("debug launch failed: launch-config type '"
                    + LAUNCH_CONFIG_TYPE_ID + "' is not registered");
        }
        try {
            ILaunchConfigurationWorkingCopy workingCopy =
                    type.newInstance(null, "mcp-debug-" + UUID.randomUUID());
            workingCopy.setAttribute(ATTR_PROJECT_NAME, projectName);
            return new Launch(workingCopy, ILaunchManager.DEBUG_MODE, null);
        } catch (CoreException e) {
            throw new ToolException("debug launch failed: cannot build launch-config: "
                    + e.getStatus().getMessage(), e);
        }
    }

    /**
     * Asks the OS for a free loopback TCP port. Bound briefly with {@code SO_REUSEADDR}, then
     * closed — there is a tiny race between close and dbgs binding the same port, but it is the
     * same race {@code HttpUtil.findFreePort()} runs internally and is acceptable in practice.
     * This wrapper exists so we can pass a non-(-1) port to {@code createLocal} (see the
     * call-site comment for the EDT bytecode bug we work around).
     */
    private static int findFreePort() throws ToolException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        } catch (IOException e) {
            throw new ToolException("debug launch failed: cannot find a free port: "
                    + e.getMessage(), e);
        }
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
