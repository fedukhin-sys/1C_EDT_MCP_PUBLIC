package ru.fedukhin.edt.mcp.tests.tools;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.edt.ListProjectsTool;

public class ListProjectsToolTest {

    @Test
    public void call_mapsProjectsToJson() throws Exception {
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        IRuntimeVersionSupport vs = mock(IRuntimeVersionSupport.class);

        IProject p1 = mock(IProject.class); when(p1.getName()).thenReturn("Conf1");
        IConfigurationProject cp1 = mock(IConfigurationProject.class);
        when(cp1.getProject()).thenReturn(p1);
        when(vs.getRuntimeVersion(p1)).thenReturn(Version.V8_3_20);

        IProject p2 = mock(IProject.class); when(p2.getName()).thenReturn("Ext1");
        IExtensionProject ep2 = mock(IExtensionProject.class);
        when(ep2.getProject()).thenReturn(p2);
        when(vs.getRuntimeVersion(p2)).thenReturn(Version.V8_3_20);

        when(pm.getProjects()).thenReturn(Arrays.<IV8Project>asList(cp1, ep2));

        ListProjectsTool tool = new ListProjectsTool(pm, vs);
        List<Map<String, Object>> result = tool.call(Collections.emptyMap());

        String expectedVersion = String.valueOf(Version.V8_3_20);
        assertEquals(2, result.size());
        assertEquals("Conf1", result.get(0).get("name"));
        assertEquals("configuration", result.get(0).get("type"));
        assertEquals(expectedVersion, result.get(0).get("version"));
        assertEquals("Ext1", result.get(1).get("name"));
        assertEquals("extension", result.get(1).get("type"));
        assertEquals(expectedVersion, result.get(1).get("version"));
    }

    @Test
    public void call_emptyWorkspaceReturnsEmptyArray() throws Exception {
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        IRuntimeVersionSupport vs = mock(IRuntimeVersionSupport.class);
        when(pm.getProjects()).thenReturn(Collections.emptyList());
        ListProjectsTool tool = new ListProjectsTool(pm, vs);
        List<Map<String, Object>> result = tool.call(Collections.emptyMap());
        assertEquals(0, result.size());
    }

    @Test
    public void metadata_isCorrect() {
        ListProjectsTool tool = new ListProjectsTool(mock(IV8ProjectManager.class),
                                                     mock(IRuntimeVersionSupport.class));
        assertEquals("list_projects", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }
}
