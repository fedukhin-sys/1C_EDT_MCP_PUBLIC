package ru.fedukhin.edt.mcp.tests.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.edt.workspace.GetProjectTool;

public class GetProjectToolTest {

    private GetProjectTool newTool(IWorkspaceRoot root, IV8ProjectManager pm,
                                   IRuntimeVersionSupport vs, IConfigurationProvider cp) {
        return new GetProjectTool(() -> root, pm, vs, cp);
    }

    @Test
    public void call_returnsConfigurationProjectDetails() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Conf1");
        when(project.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/ws/Conf1"));

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Conf1")).thenReturn(project);

        IConfigurationProject cp = mock(IConfigurationProject.class);
        when(cp.getProject()).thenReturn(project);

        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(project)).thenReturn(cp);

        IRuntimeVersionSupport vs = mock(IRuntimeVersionSupport.class);
        when(vs.getRuntimeVersion(project)).thenReturn(Version.V8_3_22);

        Configuration cfg = mock(Configuration.class);
        when(cfg.getScriptVariant()).thenReturn(ScriptVariant.ENGLISH);
        IConfigurationProvider cfgProvider = mock(IConfigurationProvider.class);
        when(cfgProvider.getConfiguration(project)).thenReturn(cfg);

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Conf1");
        Map<String, Object> result = newTool(root, pm, vs, cfgProvider).call(args);

        assertEquals("Conf1", result.get("name"));
        assertEquals("configuration", result.get("type"));
        assertEquals(String.valueOf(Version.V8_3_22), result.get("version"));
        assertEquals(Boolean.TRUE, result.get("open"));
        assertEquals(Boolean.TRUE, result.get("exists"));
        assertEquals("C:/ws/Conf1", result.get("location").toString().replace('\\', '/'));
        assertEquals("ENGLISH", result.get("scriptVariant"));
    }

    @Test
    public void call_returnsExtensionType() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Ext1");
        when(project.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/ws/Ext1"));
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Ext1")).thenReturn(project);
        IExtensionProject ep = mock(IExtensionProject.class);
        when(ep.getProject()).thenReturn(project);
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(project)).thenReturn(ep);
        IRuntimeVersionSupport vs = mock(IRuntimeVersionSupport.class);
        when(vs.getRuntimeVersion(project)).thenReturn(Version.V8_3_22);

        Map<String, Object> args = new HashMap<>(); args.put("name", "Ext1");
        Map<String, Object> result = newTool(root, pm, vs, mock(IConfigurationProvider.class)).call(args);
        assertEquals("extension", result.get("type"));
        assertNull(result.get("scriptVariant"));
    }

    @Test
    public void call_closedProject_versionIsNull() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(false);
        when(project.getName()).thenReturn("Closed1");
        when(project.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/ws/Closed1"));
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Closed1")).thenReturn(project);
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(project)).thenReturn(null);
        IRuntimeVersionSupport vs = mock(IRuntimeVersionSupport.class);
        when(vs.getRuntimeVersion(project)).thenReturn(null);

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Closed1");
        Map<String, Object> result = newTool(root, pm, vs, mock(IConfigurationProvider.class)).call(args);
        assertNull(result.get("version"));
    }

    @Test
    public void call_nonExistentProjectReturnsExistsFalse() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Missing")).thenReturn(project);

        Map<String, Object> args = new HashMap<>(); args.put("name", "Missing");
        Map<String, Object> result = newTool(
            root, mock(IV8ProjectManager.class), mock(IRuntimeVersionSupport.class),
            mock(IConfigurationProvider.class)).call(args);
        assertEquals("Missing", result.get("name"));
        assertEquals(Boolean.FALSE, result.get("exists"));
        assertFalse(result.containsKey("type"));
    }

    @Test(expected = ToolException.class)
    public void call_missingNameArgThrows() throws Exception {
        newTool(mock(IWorkspaceRoot.class), mock(IV8ProjectManager.class),
                mock(IRuntimeVersionSupport.class), mock(IConfigurationProvider.class))
            .call(new HashMap<>());
    }

    @Test
    public void metadata_isCorrect() {
        GetProjectTool tool = newTool(mock(IWorkspaceRoot.class), mock(IV8ProjectManager.class),
                mock(IRuntimeVersionSupport.class), mock(IConfigurationProvider.class));
        assertEquals("get_project", tool.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = tool.inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("name"));
    }
}
