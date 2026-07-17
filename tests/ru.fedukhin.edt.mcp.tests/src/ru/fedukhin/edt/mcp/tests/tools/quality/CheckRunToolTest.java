package ru.fedukhin.edt.mcp.tests.tools.quality;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.quality.CheckRunTool;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckMarker;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckRunResult;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckRunner;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CheckRunToolTest {

    @Test public void unknownProjectThrows() {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(false);
        when(root.getProject("Demo")).thenReturn(project);
        CheckRunner runner = mock(CheckRunner.class);

        CheckRunTool tool = new CheckRunTool(() -> root, runner);
        assertThrows(ToolException.class, () -> tool.call(Map.of("project", "Demo")));
    }

    @Test public void wholeProjectRunWithDefaults() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        when(root.getProject("Demo")).thenReturn(project);

        CheckMarker marker = new CheckMarker("u-1", "Demo", "", 12, "warning",
                "c.a", "v8codestyle", "msg", null);
        CheckRunResult res = new CheckRunResult(true, 60,
                List.of(marker),
                new CheckRunResult.SeveritySummary(0, 0, 0, 1, 0),
                Set.of());
        CheckRunner runner = mock(CheckRunner.class);
        when(runner.run(eq(project), any(), eq(null), eq(Set.of()), eq(60), eq(true))).thenReturn(res);

        CheckRunTool tool = new CheckRunTool(() -> root, runner);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of("project", "Demo"));

        assertEquals(true,    result.get("ran"));
        assertEquals(true,    result.get("completed"));
        assertEquals("Demo",  result.get("project"));
        assertEquals("project", result.get("scope"));
        assertEquals(60,      result.get("waitedSeconds"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> markers = (List<Map<String, Object>>) result.get("markers");
        assertEquals(1, markers.size());
        assertEquals("u-1", markers.get(0).get("markerId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("minor"));
    }

    @Test public void fileScopePassesScopeObject() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        when(root.getProject("Demo")).thenReturn(project);
        CheckRunner runner = mock(CheckRunner.class);
        CheckRunResult res = new CheckRunResult(true, 60, List.of(),
                new CheckRunResult.SeveritySummary(0, 0, 0, 0, 0), Set.of());
        when(runner.run(any(), any(), any(), any(), anyInt(), anyBoolean())).thenReturn(res);

        CheckRunTool tool = new CheckRunTool(() -> root, runner);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "path",    "src/CommonModules/Foo/Module.bsl"));

        assertEquals("file", result.get("scope"));
        // path уходит и в scope валидации, и в фильтр маркеров
        org.mockito.Mockito.verify(runner).run(eq(project),
                eq(List.of("/Demo/src/CommonModules/Foo/Module.bsl")),
                eq("src/CommonModules/Foo/Module.bsl"), any(), anyInt(), anyBoolean());
    }
}
