package ru.fedukhin.edt.mcp.tests.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.version.Version;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.edt.workspace.CreateProjectTool;

public class CreateProjectToolTest {

    private static IWorkspace workspaceThatRunsRunnable() throws CoreException {
        IWorkspace ws = mock(IWorkspace.class);
        doAnswer(inv -> {
            IWorkspaceRunnable r = inv.getArgument(0);
            r.run(mock(IProgressMonitor.class));
            return null;
        }).when(ws).run(any(IWorkspaceRunnable.class), any(IProgressMonitor.class));
        return ws;
    }

    private static IRuntimeRegistry registryWith(Version... versions) {
        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);
        IRuntime[] runtimes = new IRuntime[versions.length];
        for (int i = 0; i < versions.length; i++) {
            runtimes[i] = mock(IRuntime.class);
            when(runtimes[i].getVersion()).thenReturn(versions[i]);
        }
        when(rr.getRuntimes()).thenReturn(Arrays.asList(runtimes));
        return rr;
    }

    @Test
    public void call_createsConfigurationProject() throws Exception {
        IWorkspace ws = workspaceThatRunsRunnable();
        IConfigurationProjectManager cpm = mock(IConfigurationProjectManager.class);
        IProject created = mock(IProject.class);
        when(created.getName()).thenReturn("New");
        when(created.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/ws/New"));
        when(cpm.create(eq("New"), eq(Version.V8_3_22), (Configuration) any(), any(IProgressMonitor.class)))
            .thenReturn(created);

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject placeholder = mock(IProject.class);
        when(placeholder.exists()).thenReturn(false);
        when(root.getProject("New")).thenReturn(placeholder);

        CreateProjectTool tool = new CreateProjectTool(
            ws, () -> root, registryWith(Version.V8_3_22),
            cpm, mock(IExtensionProjectManager.class), mock(IExternalObjectProjectManager.class),
            mock(IV8ProjectManager.class));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "New");
        args.put("type", "configuration");
        args.put("version", String.valueOf(Version.V8_3_22));

        Map<String, Object> result = tool.call(args);
        verify(cpm).create(eq("New"), eq(Version.V8_3_22), (Configuration) any(), any(IProgressMonitor.class));
        assertEquals("New", result.get("name"));
        assertEquals("configuration", result.get("type"));
        assertEquals(String.valueOf(Version.V8_3_22), result.get("version"));
    }

    @Test
    public void call_createsExtensionProject_requiresParent() throws Exception {
        IWorkspace ws = workspaceThatRunsRunnable();
        IExtensionProjectManager epm = mock(IExtensionProjectManager.class);

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject newProject = mock(IProject.class);
        when(newProject.exists()).thenReturn(false);
        when(root.getProject("Ext1")).thenReturn(newProject);
        IProject parent = mock(IProject.class);
        when(parent.exists()).thenReturn(true);
        when(parent.isOpen()).thenReturn(true);
        when(root.getProject("Parent")).thenReturn(parent);

        IProject created = mock(IProject.class);
        when(created.getName()).thenReturn("Ext1");
        when(created.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/ws/Ext1"));
        // create_project now writes src/Configuration/Configuration.mdo for extensions (BUG-01/11).
        IFile mdo = mock(IFile.class);
        when(mdo.exists()).thenReturn(false);
        when(created.getFile("src/Configuration/Configuration.mdo")).thenReturn(mdo);
        IFolder srcFolder = mock(IFolder.class);
        when(srcFolder.exists()).thenReturn(true);
        when(created.getFolder("src")).thenReturn(srcFolder);
        IFolder cfgFolder = mock(IFolder.class);
        when(cfgFolder.exists()).thenReturn(true);
        when(created.getFolder("src/Configuration")).thenReturn(cfgFolder);
        when(epm.create(eq("Ext1"), eq(Version.V8_3_22), (Configuration) any(), eq(parent), any(IProgressMonitor.class)))
            .thenReturn(created);

        CreateProjectTool tool = new CreateProjectTool(
            ws, () -> root, registryWith(Version.V8_3_22),
            mock(IConfigurationProjectManager.class), epm, mock(IExternalObjectProjectManager.class),
            mock(IV8ProjectManager.class));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Ext1");
        args.put("type", "extension");
        args.put("version", String.valueOf(Version.V8_3_22));
        args.put("parentConfigurationName", "Parent");

        Map<String, Object> result = tool.call(args);
        verify(epm).create(eq("Ext1"), eq(Version.V8_3_22), (Configuration) any(), eq(parent), any(IProgressMonitor.class));
        verify(mdo).create(any(java.io.InputStream.class), eq(true), any(IProgressMonitor.class));
        assertEquals("extension", result.get("type"));
        assertEquals(Boolean.TRUE, result.get("configurationMdoWritten"));
    }

    @Test
    public void call_createsExternalObjectProject_requiresParent() throws Exception {
        IWorkspace ws = workspaceThatRunsRunnable();
        IExternalObjectProjectManager xpm = mock(IExternalObjectProjectManager.class);

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject newProject = mock(IProject.class);
        when(newProject.exists()).thenReturn(false);
        when(root.getProject("Ext1")).thenReturn(newProject);
        IProject parent = mock(IProject.class);
        when(parent.exists()).thenReturn(true);
        when(parent.isOpen()).thenReturn(true);
        when(root.getProject("Parent")).thenReturn(parent);

        IProject created = mock(IProject.class);
        when(created.getName()).thenReturn("Ext1");
        when(created.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/ws/Ext1"));
        when(xpm.create(eq("Ext1"), eq(Version.V8_3_22), (MdObject) any(), eq(parent), any(IProgressMonitor.class)))
            .thenReturn(created);

        CreateProjectTool tool = new CreateProjectTool(
            ws, () -> root, registryWith(Version.V8_3_22),
            mock(IConfigurationProjectManager.class), mock(IExtensionProjectManager.class), xpm,
            mock(IV8ProjectManager.class));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Ext1");
        args.put("type", "external-object");
        args.put("version", String.valueOf(Version.V8_3_22));
        args.put("parentConfigurationName", "Parent");

        Map<String, Object> result = tool.call(args);
        verify(xpm).create(eq("Ext1"), eq(Version.V8_3_22), (MdObject) any(), eq(parent), any(IProgressMonitor.class));
        assertEquals("external-object", result.get("type"));
    }

    @Test
    public void call_extensionWithoutParent_throws() throws Exception {
        IWorkspace ws = workspaceThatRunsRunnable();
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject placeholder = mock(IProject.class);
        when(placeholder.exists()).thenReturn(false);
        when(root.getProject("Ext1")).thenReturn(placeholder);
        CreateProjectTool tool = new CreateProjectTool(
            ws, () -> root, registryWith(Version.V8_3_22),
            mock(IConfigurationProjectManager.class), mock(IExtensionProjectManager.class),
            mock(IExternalObjectProjectManager.class), mock(IV8ProjectManager.class));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Ext1"); args.put("type", "extension"); args.put("version", String.valueOf(Version.V8_3_22));
        try { tool.call(args); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_duplicateName_throws() throws Exception {
        IWorkspace ws = workspaceThatRunsRunnable();
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject existing = mock(IProject.class);
        when(existing.exists()).thenReturn(true);
        when(root.getProject("Dup")).thenReturn(existing);

        CreateProjectTool tool = new CreateProjectTool(
            ws, () -> root, registryWith(Version.V8_3_22),
            mock(IConfigurationProjectManager.class), mock(IExtensionProjectManager.class),
            mock(IExternalObjectProjectManager.class), mock(IV8ProjectManager.class));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Dup"); args.put("type", "configuration"); args.put("version", String.valueOf(Version.V8_3_22));
        try { tool.call(args); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_unknownVersion_throws() throws Exception {
        IWorkspace ws = workspaceThatRunsRunnable();
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject placeholder = mock(IProject.class);
        when(placeholder.exists()).thenReturn(false);
        when(root.getProject("X")).thenReturn(placeholder);

        CreateProjectTool tool = new CreateProjectTool(
            ws, () -> root, registryWith(Version.V8_3_22),
            mock(IConfigurationProjectManager.class), mock(IExtensionProjectManager.class),
            mock(IExternalObjectProjectManager.class), mock(IV8ProjectManager.class));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "X"); args.put("type", "configuration"); args.put("version", "9.9.9");
        try { tool.call(args); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }

    @Test
    public void metadata_isCorrect() throws Exception {
        IWorkspace ws = workspaceThatRunsRunnable();
        CreateProjectTool tool = new CreateProjectTool(ws, () -> mock(IWorkspaceRoot.class),
            mock(IRuntimeRegistry.class),
            mock(IConfigurationProjectManager.class), mock(IExtensionProjectManager.class),
            mock(IExternalObjectProjectManager.class), mock(IV8ProjectManager.class));
        assertEquals("create_project", tool.name());
    }
}
