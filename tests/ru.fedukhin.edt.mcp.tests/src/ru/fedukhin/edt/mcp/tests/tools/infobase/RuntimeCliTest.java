package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
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
import com._1c.g5.v8.dt.platform.version.Version;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli.DefaultExecutableResolver;

public class RuntimeCliTest {

    @Test
    public void createFileInfobase_happy_returnsZero() throws Exception {
        Path location = Paths.get("C:/tmp/IB");
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getVersion()).thenReturn(new Version("8.3.24"));
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntime("8.3.24")).thenReturn(runtime);
        when(reg.getRuntimes()).thenReturn(Collections.singletonList(runtime));

        RuntimeCli cli = new RuntimeCli(reg,
            v -> new File("C:/Program Files/1cv8/8.3.24/bin/1cv8.exe"),
            (cmd, dir) -> {
                Process p = mock(Process.class);
                try {
                    when(p.waitFor(60L, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
                } catch (InterruptedException ignored) {}
                when(p.exitValue()).thenReturn(0);
                when(p.getErrorStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
                return p;
            });
        int code = cli.createFileInfobase(location, "8.3.24", Duration.ofSeconds(60));
        assertEquals(0, code);
    }

    @Test
    public void createFileInfobase_unknownVersion_throwsToolException() {
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntime("9.9.9")).thenReturn(null);
        when(reg.getRuntimes()).thenReturn(Collections.emptyList());

        RuntimeCli cli = new RuntimeCli(reg,
            v -> { throw new IllegalStateException("not called"); },
            (c, d) -> { throw new IllegalStateException("not called"); });
        try {
            cli.createFileInfobase(Paths.get("C:/tmp/X"), "9.9.9", Duration.ofSeconds(10));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("9.9.9"));
        }
    }

    @Test
    public void createFileInfobase_processNonZero_throwsToolException() {
        Path location = Paths.get("C:/tmp/IB");
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getVersion()).thenReturn(new Version("8.3.24"));
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntime("8.3.24")).thenReturn(runtime);
        when(reg.getRuntimes()).thenReturn(Collections.singletonList(runtime));

        RuntimeCli cli = new RuntimeCli(reg,
            v -> new File("C:/Program Files/1cv8/8.3.24/bin/1cv8.exe"),
            (cmd, dir) -> {
                Process p = mock(Process.class);
                try {
                    when(p.waitFor(60L, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
                } catch (InterruptedException ignored) {}
                when(p.exitValue()).thenReturn(7);
                when(p.getErrorStream()).thenReturn(new java.io.ByteArrayInputStream("boom".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                return p;
            });
        try {
            cli.createFileInfobase(location, "8.3.24", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("7"));
        }
    }

    @Test
    public void createFileInfobase_timeout_destroysAndThrows() throws Exception {
        Path location = Paths.get("C:/tmp/IB");
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getVersion()).thenReturn(new Version("8.3.24"));
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntime("8.3.24")).thenReturn(runtime);
        when(reg.getRuntimes()).thenReturn(Collections.singletonList(runtime));

        Process p = mock(Process.class);
        when(p.waitFor(2L, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false);
        when(p.getErrorStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));

        RuntimeCli cli = new RuntimeCli(reg,
            v -> new File("C:/Program Files/1cv8/8.3.24/bin/1cv8.exe"),
            (cmd, dir) -> p);
        try {
            cli.createFileInfobase(location, "8.3.24", Duration.ofSeconds(2));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().toLowerCase().contains("timeout"));
        }
        org.mockito.Mockito.verify(p).destroyForcibly();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void defaultResolver_happy_returnsThickClientFile() throws Exception {
        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), eq(AppArch.AUTO))).thenReturn(install);

        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        when(im.resolveByFullVersion(any(String.class), eq("8.3.24"), any(), eq(AppArch.AUTO)))
            .thenReturn(resolvable);

        ILaunchableRuntimeComponent component = mock(ILaunchableRuntimeComponent.class);
        when(component.getFile()).thenReturn(new java.io.File("C:/1cv8/8.3.24/bin/1cv8.exe"));
        IThickClientLauncher launcher = mock(IThickClientLauncher.class);
        ComponentExecutorInfo info = new ComponentExecutorInfo(install, component, launcher);
        IRuntimeComponentManager cm = mock(IRuntimeComponentManager.class);
        when(cm.resolveExecutor(eq(ILaunchableRuntimeComponent.class),
                eq(IThickClientLauncher.class), eq(install),
                eq(IRuntimeComponentTypes.THICK_CLIENT))).thenReturn(info);

        DefaultExecutableResolver resolver = new DefaultExecutableResolver(im, cm);
        java.io.File f = resolver.resolve("8.3.24");
        assertEquals(new java.io.File("C:/1cv8/8.3.24/bin/1cv8.exe").getAbsolutePath(), f.getAbsolutePath());
    }

    @Test
    public void defaultResolver_matchingRuntimeNotFound_throwsToolException() throws Exception {
        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        when(im.resolveByFullVersion(any(String.class), eq("9.9.9"), any(), eq(AppArch.AUTO)))
            .thenThrow(new MatchingRuntimeNotFound("nope"));

        DefaultExecutableResolver resolver = new DefaultExecutableResolver(im,
            mock(IRuntimeComponentManager.class));
        try {
            resolver.resolve("9.9.9");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("9.9.9"));
        }
    }
}
