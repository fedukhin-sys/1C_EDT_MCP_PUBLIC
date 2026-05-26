package ru.fedukhin.edt.mcp.tests.tools.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URL;
import org.mockito.ArgumentCaptor;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThinClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.version.Version;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientLauncher;

public class ClientLauncherTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void launch_thin_resolvesAndCallsLauncher() throws Exception {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        when(ref.getVersion()).thenReturn("8.3.24");

        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), eq(AppArch.AUTO))).thenReturn(install);

        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        when(im.resolveByVersionAndInfobase(any(String.class), any(Version.class), eq(ref),
                eq(InfobaseAccessType.CLIENT_LAUNCH), any())).thenReturn(resolvable);

        IThinClientLauncher thinLauncher = mock(IThinClientLauncher.class);
        ILaunchableRuntimeComponent component = mock(ILaunchableRuntimeComponent.class);
        Process expected = mock(Process.class);
        when(thinLauncher.startClientByInfobaseReference(eq(component), eq(ref),
                any(RuntimeExecutionArguments.class))).thenReturn(expected);

        ComponentExecutorInfo info = new ComponentExecutorInfo(install, component, thinLauncher);
        IRuntimeComponentManager cm = mock(IRuntimeComponentManager.class);
        when(cm.resolveExecutor(eq(ILaunchableRuntimeComponent.class),
                eq(IThinClientLauncher.class), eq(install),
                eq(IRuntimeComponentTypes.THIN_CLIENT))).thenReturn(info);

        ClientLauncher launcher = new ClientLauncher(im, cm);
        Process p = launcher.launch(ref, "thin", "user1", "pwd1");
        assertSame(expected, p);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void launch_thick_usesThickClientType() throws Exception {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        when(ref.getVersion()).thenReturn("8.3.24");

        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), eq(AppArch.AUTO))).thenReturn(install);

        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        when(im.resolveByVersionAndInfobase(any(String.class), any(Version.class), eq(ref),
                eq(InfobaseAccessType.CLIENT_LAUNCH), any())).thenReturn(resolvable);

        IThickClientLauncher thickLauncher = mock(IThickClientLauncher.class);
        ILaunchableRuntimeComponent component = mock(ILaunchableRuntimeComponent.class);
        Process expected = mock(Process.class);
        when(thickLauncher.startClientByInfobaseReference(eq(component), eq(ref),
                any(RuntimeExecutionArguments.class))).thenReturn(expected);

        ComponentExecutorInfo info = new ComponentExecutorInfo(install, component, thickLauncher);
        IRuntimeComponentManager cm = mock(IRuntimeComponentManager.class);
        when(cm.resolveExecutor(eq(ILaunchableRuntimeComponent.class),
                eq(IThickClientLauncher.class), eq(install),
                eq(IRuntimeComponentTypes.THICK_CLIENT))).thenReturn(info);

        ClientLauncher launcher = new ClientLauncher(im, cm);
        Process p = launcher.launch(ref, "thick", null, null);
        assertSame(expected, p);
    }

    @Test
    public void launch_unknownClientType_throws() {
        ClientLauncher launcher = new ClientLauncher(
            mock(IResolvableRuntimeInstallationManager.class),
            mock(IRuntimeComponentManager.class));
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getVersion()).thenReturn("8.3.24");
        try {
            launcher.launch(ref, "web", null, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().toLowerCase().contains("client type"));
        }
    }

    @Test
    public void launch_versionUnparsable_throws() {
        ClientLauncher launcher = new ClientLauncher(
            mock(IResolvableRuntimeInstallationManager.class),
            mock(IRuntimeComponentManager.class));
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getVersion()).thenReturn("not-a-version");
        try {
            launcher.launch(ref, "thin", null, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().toLowerCase().contains("version"));
        }
    }

    @Test
    public void launch_matchingRuntimeNotFound_throwsToolException() throws Exception {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        when(ref.getVersion()).thenReturn("8.3.24");

        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        when(im.resolveByVersionAndInfobase(any(String.class), any(Version.class), eq(ref),
                eq(InfobaseAccessType.CLIENT_LAUNCH), any()))
            .thenThrow(new MatchingRuntimeNotFound("nope"));

        ClientLauncher launcher = new ClientLauncher(im, mock(IRuntimeComponentManager.class));
        try {
            launcher.launch(ref, "thin", null, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("Demo"));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void launch_executorThrowsRuntimeExecutionException_wrappedAsToolException() throws Exception {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        when(ref.getVersion()).thenReturn("8.3.24");

        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), eq(AppArch.AUTO))).thenReturn(install);

        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        when(im.resolveByVersionAndInfobase(any(String.class), any(Version.class), eq(ref),
                eq(InfobaseAccessType.CLIENT_LAUNCH), any())).thenReturn(resolvable);

        IThinClientLauncher thinLauncher = mock(IThinClientLauncher.class);
        ILaunchableRuntimeComponent component = mock(ILaunchableRuntimeComponent.class);
        when(thinLauncher.startClientByInfobaseReference(any(), any(), any()))
            .thenThrow(new RuntimeExecutionException("boom"));

        ComponentExecutorInfo info = new ComponentExecutorInfo(install, component, thinLauncher);
        IRuntimeComponentManager cm = mock(IRuntimeComponentManager.class);
        when(cm.resolveExecutor(any(), any(), any(), any())).thenReturn(info);

        ClientLauncher launcher = new ClientLauncher(im, cm);
        try {
            launcher.launch(ref, "thin", null, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("boom"));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void launchForDebug_setsDebugServerUrlOnArguments() throws Exception {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        when(ref.getVersion()).thenReturn("8.3.24");

        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), eq(AppArch.AUTO))).thenReturn(install);

        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        when(im.resolveByVersionAndInfobase(any(String.class), any(Version.class), eq(ref),
                eq(InfobaseAccessType.CLIENT_LAUNCH), any())).thenReturn(resolvable);

        IThinClientLauncher thinLauncher = mock(IThinClientLauncher.class);
        ILaunchableRuntimeComponent component = mock(ILaunchableRuntimeComponent.class);
        Process expected = mock(Process.class);
        ArgumentCaptor<RuntimeExecutionArguments> argsCap =
                ArgumentCaptor.forClass(RuntimeExecutionArguments.class);
        when(thinLauncher.startClientByInfobaseReference(eq(component), eq(ref), argsCap.capture()))
                .thenReturn(expected);

        ComponentExecutorInfo info = new ComponentExecutorInfo(install, component, thinLauncher);
        IRuntimeComponentManager cm = mock(IRuntimeComponentManager.class);
        when(cm.resolveExecutor(eq(ILaunchableRuntimeComponent.class),
                eq(IThinClientLauncher.class), eq(install),
                eq(IRuntimeComponentTypes.THIN_CLIENT))).thenReturn(info);

        URL dbgUrl = new URL("http://localhost:1560/");
        ClientLauncher launcher = new ClientLauncher(im, cm);
        Process p = launcher.launchForDebug(ref, "thin", "user1", "pwd1", dbgUrl);

        assertSame(expected, p);
        assertEquals(dbgUrl, argsCap.getValue().getDebugServerUrl());
    }
}
