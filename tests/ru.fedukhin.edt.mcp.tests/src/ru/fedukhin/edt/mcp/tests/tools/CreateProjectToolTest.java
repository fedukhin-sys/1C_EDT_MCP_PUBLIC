package ru.fedukhin.edt.mcp.tests.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import org.mockito.ArgumentCaptor;
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

    /** A minimal parent configuration Configuration.mdo with one Russian language. */
    private static final String PARENT_CONFIG_MDO =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<mdclass:Configuration xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"p-cfg\">\n"
      + "  <name>Parent</name>\n"
      + "  <languages uuid=\"0663bf5b-bcba-4a40-a862-a0b3baa2d884\">\n"
      + "    <name>Русский</name>\n"
      + "    <synonym><key>ru</key><value>Русский</value></synonym>\n"
      + "    <languageCode>ru</languageCode>\n"
      + "  </languages>\n"
      + "</mdclass:Configuration>\n";

    /** Stubs the parent project's src/Configuration/Configuration.mdo with the given XML. */
    private static void stubParentConfigMdo(IProject parent, String xml) throws CoreException {
        IFile parentMdo = mock(IFile.class);
        when(parentMdo.exists()).thenReturn(true);
        when(parentMdo.getContents()).thenAnswer(inv ->
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        when(parent.getFile("src/Configuration/Configuration.mdo")).thenReturn(parentMdo);
    }

    /** Wires the extension's own (not-yet-existing) Configuration.mdo file and src folders. */
    private static IFile stubExtensionConfigMdo(IProject created) {
        IFile mdo = mock(IFile.class);
        when(mdo.exists()).thenReturn(false);
        when(created.getFile("src/Configuration/Configuration.mdo")).thenReturn(mdo);
        IFolder srcFolder = mock(IFolder.class);
        when(srcFolder.exists()).thenReturn(true);
        when(created.getFolder("src")).thenReturn(srcFolder);
        IFolder cfgFolder = mock(IFolder.class);
        when(cfgFolder.exists()).thenReturn(true);
        when(created.getFolder("src/Configuration")).thenReturn(cfgFolder);
        return mdo;
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
        stubParentConfigMdo(parent, PARENT_CONFIG_MDO);

        IProject created = mock(IProject.class);
        when(created.getName()).thenReturn("Ext1");
        when(created.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/ws/Ext1"));
        // create_project now writes src/Configuration/Configuration.mdo for extensions (BUG-01/11).
        IFile mdo = stubExtensionConfigMdo(created);
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

        ArgumentCaptor<InputStream> contentCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(mdo).create(contentCaptor.capture(), eq(true), any(IProgressMonitor.class));
        String written = new String(contentCaptor.getValue().readAllBytes(), StandardCharsets.UTF_8);
        // BUG-NEW-A fix: the adopted language must reference the parent language's real uuid.
        assertTrue("extendedConfigurationObject must use the parent language uuid",
            written.contains("extendedConfigurationObject=\"0663bf5b-bcba-4a40-a862-a0b3baa2d884\""));
        assertTrue("adopted language name comes from the parent",
            written.contains("<name>Русский</name>"));
        assertTrue("default language references the resolved name",
            written.contains("<defaultLanguage>Language.Русский</defaultLanguage>"));

        assertEquals("extension", result.get("type"));
        assertEquals(Boolean.TRUE, result.get("configurationMdoWritten"));
        assertEquals("parent language resolved — no warning expected", null, result.get("warning"));
    }

    @Test
    public void call_extensionWithoutParentLanguage_warns() throws Exception {
        IWorkspace ws = workspaceThatRunsRunnable();
        IExtensionProjectManager epm = mock(IExtensionProjectManager.class);

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject newProject = mock(IProject.class);
        when(newProject.exists()).thenReturn(false);
        when(root.getProject("Ext2")).thenReturn(newProject);
        IProject parent = mock(IProject.class);
        when(parent.exists()).thenReturn(true);
        when(parent.isOpen()).thenReturn(true);
        when(root.getProject("Parent")).thenReturn(parent);
        // parent has no readable Configuration.mdo -> language uuid cannot be resolved
        IFile parentMdo = mock(IFile.class);
        when(parentMdo.exists()).thenReturn(false);
        when(parent.getFile("src/Configuration/Configuration.mdo")).thenReturn(parentMdo);

        IProject created = mock(IProject.class);
        when(created.getName()).thenReturn("Ext2");
        when(created.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/ws/Ext2"));
        IFile mdo = stubExtensionConfigMdo(created);
        when(epm.create(eq("Ext2"), eq(Version.V8_3_22), (Configuration) any(), eq(parent), any(IProgressMonitor.class)))
            .thenReturn(created);

        CreateProjectTool tool = new CreateProjectTool(
            ws, () -> root, registryWith(Version.V8_3_22),
            mock(IConfigurationProjectManager.class), epm, mock(IExternalObjectProjectManager.class),
            mock(IV8ProjectManager.class));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Ext2");
        args.put("type", "extension");
        args.put("version", String.valueOf(Version.V8_3_22));
        args.put("parentConfigurationName", "Parent");

        Map<String, Object> result = tool.call(args);
        verify(mdo).create(any(InputStream.class), eq(true), any(IProgressMonitor.class));
        assertEquals(Boolean.TRUE, result.get("configurationMdoWritten"));
        assertTrue("a warning must be reported when the parent language cannot be resolved",
            result.get("warning") instanceof String);
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
