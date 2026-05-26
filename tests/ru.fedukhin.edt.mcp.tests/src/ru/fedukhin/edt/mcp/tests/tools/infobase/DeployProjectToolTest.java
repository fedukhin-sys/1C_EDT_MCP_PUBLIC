package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.DeployProjectTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseDeployer;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

public class DeployProjectToolTest {

    @Test
    public void call_happy_returnsDeployedTrue() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("MyConf")).thenReturn(project);
        InfobaseReference ref = mock(InfobaseReference.class);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("Demo")).thenReturn(Optional.of(ref));
        InfobaseDeployer deployer = mock(InfobaseDeployer.class);
        when(deployer.deployWithTimeout(eq(project), eq(ref), eq(false), anyInt()))
            .thenReturn(new InfobaseDeployer.DeployResult(true, 1234L));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "MyConf"); args.put("infobase", "Demo");
        Map<String, Object> out = new DeployProjectTool(() -> root, registry, deployer).call(args);
        assertEquals("MyConf", out.get("project"));
        assertEquals("Demo", out.get("infobase"));
        assertTrue((Boolean) out.get("deployed"));
        assertEquals(1234L, ((Number) out.get("durationMs")).longValue());
    }

    @Test
    public void call_forceTrue_passesForceToDeployer() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("MyConf")).thenReturn(project);
        InfobaseReference ref = mock(InfobaseReference.class);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("Demo")).thenReturn(Optional.of(ref));
        InfobaseDeployer deployer = mock(InfobaseDeployer.class);
        when(deployer.deployWithTimeout(eq(project), eq(ref), eq(true), anyInt()))
            .thenReturn(new InfobaseDeployer.DeployResult(true, 1L));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "MyConf"); args.put("infobase", "Demo"); args.put("force", true);
        new DeployProjectTool(() -> root, registry, deployer).call(args);
        verify(deployer).deployWithTimeout(eq(project), eq(ref), eq(true), anyInt());
    }

    @Test
    public void call_deployerThrows_propagates() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("MyConf")).thenReturn(project);
        InfobaseReference ref = mock(InfobaseReference.class);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("Demo")).thenReturn(Optional.of(ref));
        InfobaseDeployer deployer = mock(InfobaseDeployer.class);
        when(deployer.deployWithTimeout(any(), any(), any(Boolean.class), anyInt()))
            .thenThrow(new ToolException("deploy failed: conflict X"));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "MyConf"); args.put("infobase", "Demo");
        try {
            new DeployProjectTool(() -> root, registry, deployer).call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("conflict X"));
        }
    }

    @Test
    public void call_projectNotOpen_throws() {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("X")).thenReturn(project);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "X"); args.put("infobase", "Demo");
        try {
            new DeployProjectTool(() -> root, mock(InfobaseRegistry.class), mock(InfobaseDeployer.class)).call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_infobaseNotFound_throws() {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("MyConf")).thenReturn(project);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("None")).thenReturn(Optional.empty());

        Map<String, Object> args = new HashMap<>();
        args.put("project", "MyConf"); args.put("infobase", "None");
        try {
            new DeployProjectTool(() -> root, registry, mock(InfobaseDeployer.class)).call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_timeoutSecondsDefault_passes600() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("MyConf")).thenReturn(project);
        InfobaseReference ref = mock(InfobaseReference.class);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("Demo")).thenReturn(Optional.of(ref));
        InfobaseDeployer deployer = mock(InfobaseDeployer.class);
        when(deployer.deployWithTimeout(eq(project), eq(ref), eq(false), eq(600)))
            .thenReturn(new InfobaseDeployer.DeployResult(true, 0L));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "MyConf"); args.put("infobase", "Demo");
        new DeployProjectTool(() -> root, registry, deployer).call(args);
        verify(deployer).deployWithTimeout(eq(project), eq(ref), eq(false), eq(600));
    }

    @Test
    public void call_timeoutSecondsCustom_passesToDeployer() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("MyConf")).thenReturn(project);
        InfobaseReference ref = mock(InfobaseReference.class);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("Demo")).thenReturn(Optional.of(ref));
        InfobaseDeployer deployer = mock(InfobaseDeployer.class);
        when(deployer.deployWithTimeout(eq(project), eq(ref), eq(false), eq(120)))
            .thenReturn(new InfobaseDeployer.DeployResult(true, 0L));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "MyConf"); args.put("infobase", "Demo"); args.put("timeoutSeconds", 120);
        new DeployProjectTool(() -> root, registry, deployer).call(args);
        verify(deployer).deployWithTimeout(eq(project), eq(ref), eq(false), eq(120));
    }

    @Test
    public void call_timeoutSecondsOutOfRange_throws() {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("MyConf")).thenReturn(project);
        InfobaseReference ref = mock(InfobaseReference.class);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("Demo")).thenReturn(Optional.of(ref));

        DeployProjectTool tool = new DeployProjectTool(() -> root, registry, mock(InfobaseDeployer.class));
        for (int bad : new int[]{29, 3601}) {
            Map<String, Object> args = new HashMap<>();
            args.put("project", "MyConf"); args.put("infobase", "Demo"); args.put("timeoutSeconds", bad);
            try {
                tool.call(args);
                fail("expected ToolException for timeoutSeconds=" + bad);
            } catch (ToolException e) {
                assertTrue("expected '[30, 3600]' in message, was: " + e.getMessage(),
                    e.getMessage().contains("[30, 3600]"));
                assertTrue("expected the bad value in message, was: " + e.getMessage(),
                    e.getMessage().contains(String.valueOf(bad)));
            }
        }
    }
}
