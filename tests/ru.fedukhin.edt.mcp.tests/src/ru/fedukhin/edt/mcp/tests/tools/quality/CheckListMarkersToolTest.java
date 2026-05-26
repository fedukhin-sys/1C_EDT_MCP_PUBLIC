package ru.fedukhin.edt.mcp.tests.tools.quality;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.quality.CheckListMarkersTool;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckMarker;
import ru.fedukhin.edt.mcp.tools.quality.internal.MarkerReader;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CheckListMarkersToolTest {

    @Test public void readsAndForwardsFilters() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        when(root.getProject("Demo")).thenReturn(project);

        CheckMarker m = new CheckMarker("u", "Demo", "src/X.bsl", 7, "error",
                "c.a", "v8codestyle", "msg", null);
        MarkerReader reader = mock(MarkerReader.class);
        when(reader.read(eq(project), eq("src/X.bsl"), eq("error"),
                eq(Set.of("c.a")), eq("v8codestyle"))).thenReturn(List.of(m));

        CheckListMarkersTool tool = new CheckListMarkersTool(() -> root, reader);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project",  "Demo",
                "path",     "src/X.bsl",
                "severity", "error",
                "checkId",  "c.a",
                "source",   "v8codestyle"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> markers = (List<Map<String, Object>>) result.get("markers");
        assertEquals(1, markers.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("major"));
    }

    @Test public void unknownProjectThrows() {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(false);
        when(root.getProject(any())).thenReturn(project);
        MarkerReader reader = mock(MarkerReader.class);

        CheckListMarkersTool tool = new CheckListMarkersTool(() -> root, reader);
        assertThrows(ToolException.class, () -> tool.call(Map.of("project", "Missing")));
    }
}
