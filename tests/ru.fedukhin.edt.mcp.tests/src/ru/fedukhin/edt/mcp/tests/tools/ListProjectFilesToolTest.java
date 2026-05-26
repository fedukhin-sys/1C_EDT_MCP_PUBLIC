package ru.fedukhin.edt.mcp.tests.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.Path;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.edt.workspace.ListProjectFilesTool;

public class ListProjectFilesToolTest {

    @Test
    public void call_returnsFilesAndFolders_relativePaths() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("P");
        when(project.getFullPath()).thenReturn(new Path("/P"));

        IFolder srcFolder = mock(IFolder.class);
        when(srcFolder.getType()).thenReturn(IResource.FOLDER);
        when(srcFolder.getFullPath()).thenReturn(new Path("/P/src"));

        IFile bsl = mock(IFile.class);
        when(bsl.getType()).thenReturn(IResource.FILE);
        when(bsl.getFullPath()).thenReturn(new Path("/P/src/Module.bsl"));

        IFile projectMeta = mock(IFile.class);
        when(projectMeta.getType()).thenReturn(IResource.FILE);
        when(projectMeta.getFullPath()).thenReturn(new Path("/P/.project"));

        when(srcFolder.members()).thenReturn(new IResource[] { bsl });
        when(project.members()).thenReturn(new IResource[] { srcFolder, projectMeta });

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        Map<String, Object> args = new HashMap<>(); args.put("name", "P");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) new ListProjectFilesTool(() -> root).call(args);

        assertEquals(3, result.size());
        // Sorted lexicographically.
        assertEquals(".project", result.get(0).get("path"));
        assertEquals("file", result.get(0).get("type"));
        assertEquals("src", result.get(1).get("path"));
        assertEquals("folder", result.get(1).get("type"));
        assertEquals("src/Module.bsl", result.get(2).get("path"));
        assertEquals("file", result.get(2).get("type"));
    }

    @Test
    public void call_filtersByGlob() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("P");
        when(project.getFullPath()).thenReturn(new Path("/P"));

        IFile bsl = mock(IFile.class);
        when(bsl.getType()).thenReturn(IResource.FILE);
        when(bsl.getFullPath()).thenReturn(new Path("/P/A.bsl"));
        IFile xml = mock(IFile.class);
        when(xml.getType()).thenReturn(IResource.FILE);
        when(xml.getFullPath()).thenReturn(new Path("/P/A.xml"));

        when(project.members()).thenReturn(new IResource[] { bsl, xml });

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        Map<String, Object> args = new HashMap<>();
        args.put("name", "P");
        args.put("glob", "*.bsl");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) new ListProjectFilesTool(() -> root).call(args);
        assertEquals(1, result.size());
        assertEquals("A.bsl", result.get(0).get("path"));
    }

    @Test(expected = ToolException.class)
    public void call_nonExistentProjectThrows() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Missing")).thenReturn(project);
        Map<String, Object> args = new HashMap<>(); args.put("name", "Missing");
        new ListProjectFilesTool(() -> root).call(args);
    }

    @Test(expected = ToolException.class)
    public void call_closedProjectThrows() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Closed")).thenReturn(project);
        Map<String, Object> args = new HashMap<>(); args.put("name", "Closed");
        new ListProjectFilesTool(() -> root).call(args);
    }

    @Test
    public void metadata_isCorrect() {
        ListProjectFilesTool tool = new ListProjectFilesTool(() -> mock(IWorkspaceRoot.class));
        assertEquals("list_project_files", tool.name());
        assertTrue(tool.description().toLowerCase().contains("list"));
    }
}
