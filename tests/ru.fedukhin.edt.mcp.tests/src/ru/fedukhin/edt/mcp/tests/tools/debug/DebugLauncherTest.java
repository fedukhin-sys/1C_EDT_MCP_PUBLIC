package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.util.Optional;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTargetManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientLauncher;
import ru.fedukhin.edt.mcp.tools.client.internal.InfobaseLookup;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugLaunchResult;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugLauncher;

public class DebugLauncherTest {

    private final InfobaseLookup lookup = mock(InfobaseLookup.class);
    private final IResolvableRuntimeInstallationManager installs =
            mock(IResolvableRuntimeInstallationManager.class);
    private final IRuntimeDebugClientTargetManager targets =
            mock(IRuntimeDebugClientTargetManager.class);
    private final ClientLauncher clientLauncher = mock(ClientLauncher.class);
    private final DebugLauncher launcher =
            new DebugLauncher(lookup, installs, targets, clientLauncher);

    private InfobaseReference infobase(String name) {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn(name);
        when(ref.getVersion()).thenReturn("8.3.24");
        return ref;
    }

    @Test
    public void launch_happyPath_createsTargetThenAttachesClient() throws Exception {
        InfobaseReference ref = infobase("DemoIB");
        when(lookup.findByName("DemoIB")).thenReturn(Optional.of(ref));

        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), any())).thenReturn(install);
        when(installs.resolveByVersionAndInfobase(any(), any(), eq(ref), any(), any()))
                .thenReturn(resolvable);

        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getDebugServerUrl()).thenReturn("http://localhost:1560/");
        when(targets.createLocal(eq(install), (InfobaseReference) eq(ref), anyInt(), any(ILaunch.class)))
                .thenReturn(target);

        Process clientProc = mock(Process.class);
        when(clientLauncher.launchForDebug(eq(ref), eq("thin"), eq("u"), eq("p"),
                eq(new URL("http://localhost:1560/")))).thenReturn(clientProc);

        DebugLaunchResult result = launcher.launch("DemoIB", "thin", "u", "p");

        assertSame(target, result.target());
        assertSame(clientProc, result.clientProcess());
        assertNotNull(result.launch());
        assertEquals("DemoIB", result.infobaseName());
        assertEquals("thin", result.clientType());
    }

    @Test
    public void launch_infobaseNotFound_throwsToolException() {
        when(lookup.findByName("Nope")).thenReturn(Optional.empty());
        try {
            launcher.launch("Nope", "thin", null, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("Nope"));
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    @Test
    public void launch_passesLocalRuntimeWorkingCopyConfig_withProjectName() throws Exception {
        // Regression test for the spike's [best-inference-needs-smoke] item that the live IDE
        // smoke on 2026-05-15 reproduced: createLocal called with launch.getLaunchConfiguration()
        // == null fails inside getConfigurationScriptVariant (surfaces as
        // "Не найден свободный порт на сервере отладки"). The fix wraps a minimal LocalRuntime
        // working-copy with ATTR_PROJECT_NAME; locator stays null (xtext.ui wall, spike risk #1).
        InfobaseReference ref = infobase("DemoIB");
        when(lookup.findByName("DemoIB")).thenReturn(Optional.of(ref));

        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), any())).thenReturn(install);
        when(installs.resolveByVersionAndInfobase(any(), any(), eq(ref), any(), any()))
                .thenReturn(resolvable);

        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getDebugServerUrl()).thenReturn("http://localhost:1551/");
        ArgumentCaptor<ILaunch> launchCap = ArgumentCaptor.forClass(ILaunch.class);
        when(targets.createLocal(eq(install), (InfobaseReference) eq(ref), anyInt(), launchCap.capture()))
                .thenReturn(target);
        when(clientLauncher.launchForDebug(eq(ref), eq("thin"), any(), any(), any(URL.class)))
                .thenReturn(mock(Process.class));

        launcher.launch("DemoIB", "thin", null, null);

        ILaunch capturedLaunch = launchCap.getValue();
        ILaunchConfiguration cfg = capturedLaunch.getLaunchConfiguration();
        assertNotNull("launch config MUST be non-null — that is the whole point of the fix", cfg);
        assertEquals("com._1c.g5.v8.dt.debug.core.LocalRuntime", cfg.getType().getIdentifier());
        assertEquals("DemoIB",
                cfg.getAttribute("com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME", ""));
    }

    @Test
    public void launch_passesNonNegativePortToCreateLocal() throws Exception {
        // Regression test for the EDT bytecode bug found via the diag patch (PR #4):
        // RuntimeDebugClientTargetManager.runDebugServerAndCreateDebugTarget with port=-1
        // ALWAYS throws CoreException("Не найден свободный порт на сервере отладки") because
        // the re-check of port after findFreePort() reads the unchanged param instead of the
        // freshly-allocated local. DebugLauncher must compute a free port itself and pass it
        // explicitly so the EDT bytecode jumps to the non-buggy branch.
        InfobaseReference ref = infobase("DemoIB");
        when(lookup.findByName("DemoIB")).thenReturn(Optional.of(ref));

        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), any())).thenReturn(install);
        when(installs.resolveByVersionAndInfobase(any(), any(), eq(ref), any(), any()))
                .thenReturn(resolvable);

        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getDebugServerUrl()).thenReturn("http://localhost:1551/");
        ArgumentCaptor<Integer> portCap = ArgumentCaptor.forClass(Integer.class);
        when(targets.createLocal(eq(install), (InfobaseReference) eq(ref), portCap.capture(),
                any(ILaunch.class))).thenReturn(target);
        when(clientLauncher.launchForDebug(eq(ref), eq("thin"), any(), any(), any(URL.class)))
                .thenReturn(mock(Process.class));

        launcher.launch("DemoIB", "thin", null, null);

        int capturedPort = portCap.getValue();
        assertNotEquals("port MUST NOT be -1 (EDT bytecode bug workaround)", -1, capturedPort);
        assertTrue("port must be in valid TCP range, got " + capturedPort,
                capturedPort > 0 && capturedPort < 65536);
    }

    @Test
    public void launch_clientLaunchFails_disconnectsTargetSoDbgsDoesNotLeak() throws Exception {
        // Регресс на утечку dbgs: createLocal уже поднял сервер отладки и подключился к нему,
        // а вторая фаза (запуск клиента) упала. Без cleanup'а target остаётся подключённым,
        // и следующий debug_client по этой же ИБ падает «Попытка повторного соединения
        // с сервером отладки» (та же симптоматика, что чинил DebugSession.terminate()).
        InfobaseReference ref = infobase("DemoIB");
        when(lookup.findByName("DemoIB")).thenReturn(Optional.of(ref));
        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), any())).thenReturn(install);
        when(installs.resolveByVersionAndInfobase(any(), any(), eq(ref), any(), any()))
                .thenReturn(resolvable);

        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getDebugServerUrl()).thenReturn("http://localhost:1560/");
        when(target.canDisconnect()).thenReturn(true);
        when(targets.createLocal(eq(install), (InfobaseReference) eq(ref), anyInt(), any(ILaunch.class)))
                .thenReturn(target);
        when(clientLauncher.launchForDebug(eq(ref), eq("thin"), any(), any(), any(URL.class)))
                .thenThrow(new ToolException("1cv8 boom"));

        try {
            launcher.launch("DemoIB", "thin", null, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("1cv8 boom"));
        }

        verify(target).disconnect();
    }

    @Test
    public void launch_malformedDebugServerUrl_disconnectsTarget() throws Exception {
        InfobaseReference ref = infobase("DemoIB");
        when(lookup.findByName("DemoIB")).thenReturn(Optional.of(ref));
        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), any())).thenReturn(install);
        when(installs.resolveByVersionAndInfobase(any(), any(), eq(ref), any(), any()))
                .thenReturn(resolvable);

        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getDebugServerUrl()).thenReturn("не-url");
        when(target.canDisconnect()).thenReturn(true);
        when(targets.createLocal(eq(install), (InfobaseReference) eq(ref), anyInt(), any(ILaunch.class)))
                .thenReturn(target);

        try {
            launcher.launch("DemoIB", "thin", null, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("bad debug server url"));
        }

        verify(target).disconnect();
    }

    @Test
    public void launch_secondPhaseFails_terminatesTargetWhenDisconnectUnsupported() throws Exception {
        InfobaseReference ref = infobase("DemoIB");
        when(lookup.findByName("DemoIB")).thenReturn(Optional.of(ref));
        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), any())).thenReturn(install);
        when(installs.resolveByVersionAndInfobase(any(), any(), eq(ref), any(), any()))
                .thenReturn(resolvable);

        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.getDebugServerUrl()).thenReturn("http://localhost:1560/");
        when(target.canDisconnect()).thenReturn(false);
        when(target.canTerminate()).thenReturn(true);
        when(targets.createLocal(eq(install), (InfobaseReference) eq(ref), anyInt(), any(ILaunch.class)))
                .thenReturn(target);
        when(clientLauncher.launchForDebug(eq(ref), eq("thin"), any(), any(), any(URL.class)))
                .thenThrow(new ToolException("1cv8 boom"));

        try {
            launcher.launch("DemoIB", "thin", null, null);
            fail("expected ToolException");
        } catch (ToolException expected) {
            // ожидаемо
        }

        verify(target).terminate();
    }

    @Test
    public void launch_createLocalFails_throwsDebugLaunchFailed() throws Exception {
        InfobaseReference ref = infobase("DemoIB");
        when(lookup.findByName("DemoIB")).thenReturn(Optional.of(ref));
        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), any())).thenReturn(install);
        when(installs.resolveByVersionAndInfobase(any(), any(), eq(ref), any(), any()))
                .thenReturn(resolvable);
        when(targets.createLocal(any(RuntimeInstallation.class), any(InfobaseReference.class), anyInt(), any(ILaunch.class)))
                .thenThrow(new RuntimeException("dbgs boom"));
        try {
            launcher.launch("DemoIB", "thin", null, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().toLowerCase().contains("debug launch failed"));
            assertTrue(e.getMessage().contains("dbgs boom"));
        }
    }
}
