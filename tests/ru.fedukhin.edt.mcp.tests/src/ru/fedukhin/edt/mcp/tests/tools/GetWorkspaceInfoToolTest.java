package ru.fedukhin.edt.mcp.tests.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.version.Version;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.edt.workspace.GetWorkspaceInfoTool;

public class GetWorkspaceInfoToolTest {

    @Test
    public void call_returnsRootAndCounts() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/ws"));
        IProject open = mock(IProject.class);
        when(open.isOpen()).thenReturn(true);
        IProject closed = mock(IProject.class);
        when(closed.isOpen()).thenReturn(false);
        when(root.getProjects()).thenReturn(new IProject[] { open, closed });

        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);
        IRuntime r1 = mock(IRuntime.class);
        when(r1.getVersion()).thenReturn(Version.V8_3_22);
        IRuntime r2 = mock(IRuntime.class);
        when(r2.getVersion()).thenReturn(Version.V8_3_24);
        when(rr.getRuntimes()).thenReturn(Arrays.asList(r1, r2));

        GetWorkspaceInfoTool tool = new GetWorkspaceInfoTool(() -> root, rr);
        Map<String, Object> result = tool.call(Collections.emptyMap());

        assertEquals("C:/ws", result.get("root").toString().replace('\\', '/'));
        assertEquals(2, result.get("projectCount"));
        assertEquals(1, result.get("openProjectCount"));
        @SuppressWarnings("unchecked")
        List<String> versions = (List<String>) result.get("runtimeVersions");
        assertNotNull(versions);
        assertEquals(2, versions.size());
        assertTrue(versions.contains(String.valueOf(Version.V8_3_22)));
        assertTrue(versions.contains(String.valueOf(Version.V8_3_24)));
        assertEquals(Version.V8_3_24.toString(), result.get("defaultRuntimeVersion"));
    }

    @Test
    public void call_emptyWorkspaceAndRegistry() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/empty"));
        when(root.getProjects()).thenReturn(new IProject[0]);
        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);
        when(rr.getRuntimes()).thenReturn(Collections.emptyList());

        GetWorkspaceInfoTool tool = new GetWorkspaceInfoTool(() -> root, rr);
        Map<String, Object> result = tool.call(Collections.emptyMap());

        assertEquals(0, result.get("projectCount"));
        assertEquals(0, result.get("openProjectCount"));
        assertEquals(0, ((List<?>) result.get("runtimeVersions")).size());
        assertNull(result.get("defaultRuntimeVersion"));
    }

    /**
     * При нескольких инстанциях EDT сессия обязана уметь сверить, куда попала:
     * инструмент отдаёт список открытых проектов, занятый порт и pid процесса.
     */
    @Test
    public void call_reportsProjectsPortAndPid() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("E:/EDTProjects/Demo"));
        IProject open = mock(IProject.class);
        when(open.isOpen()).thenReturn(true);
        when(open.getName()).thenReturn("Demo");
        IProject closed = mock(IProject.class);
        when(closed.isOpen()).thenReturn(false);
        when(closed.getName()).thenReturn("Demo.Старый");
        when(root.getProjects()).thenReturn(new IProject[] { open, closed });

        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);
        when(rr.getRuntimes()).thenReturn(Collections.emptyList());

        GetWorkspaceInfoTool tool = new GetWorkspaceInfoTool(() -> root, rr, () -> 3002);
        Map<String, Object> out = tool.call(Collections.emptyMap());

        assertEquals(3002, out.get("port"));
        assertEquals(ProcessHandle.current().pid(), out.get("pid"));
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) out.get("projects");
        assertTrue("должен перечислять открытые проекты", names.contains("Demo"));
        assertTrue("закрытые проекты в список не попадают", !names.contains("Demo.Старый"));
    }

    /** Без шины состояния (старый seam) порт неизвестен — это 0, а не падение. */
    @Test
    public void call_withoutStateBus_reportsZeroPort() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("C:/ws"));
        when(root.getProjects()).thenReturn(new IProject[0]);
        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);
        when(rr.getRuntimes()).thenReturn(Collections.emptyList());

        Map<String, Object> out = new GetWorkspaceInfoTool(() -> root, rr).call(Collections.emptyMap());

        assertEquals(0, out.get("port"));
    }

    @Test
    public void metadata_isCorrect() {
        GetWorkspaceInfoTool tool = new GetWorkspaceInfoTool(() -> mock(IWorkspaceRoot.class), mock(IRuntimeRegistry.class));
        assertEquals("get_workspace_info", tool.name());
        assertEquals("object", tool.inputSchema().get("type"));
    }
}
