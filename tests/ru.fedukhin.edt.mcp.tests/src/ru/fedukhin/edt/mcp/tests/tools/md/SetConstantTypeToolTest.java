package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.SetConstantTypeTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringParser;

public class SetConstantTypeToolTest {

    private final TypeStringParser parser = new TypeStringParser();

    private IProject mockProject(IFile mdoFile) {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getFile("src/Constants/Тест/Тест.mdo")).thenReturn(mdoFile);
        return project;
    }

    @Test
    public void setsSingleRefType() throws Exception {
        IFile mdoFile = mock(IFile.class);
        when(mdoFile.exists()).thenReturn(true);
        IProject project = mockProject(mdoFile);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        MdoFileEditor editor = mock(MdoFileEditor.class);
        when(editor.setConstantType(eq(mdoFile), anyList())).thenReturn(true);

        SetConstantTypeTool tool = new SetConstantTypeTool(() -> root, editor, parser);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "fqn",     "Constant.Тест",
                "type",    "CatalogRef.Партнеры"));

        assertEquals("Constant.Тест", result.get("fqn"));
        assertEquals(true, result.get("replaced"));

        ArgumentCaptor<List> cap = ArgumentCaptor.forClass(List.class);
        verify(editor).setConstantType(eq(mdoFile), cap.capture());
        assertEquals(List.of("CatalogRef.Партнеры"), cap.getValue());
    }

    @Test
    public void setsCompositeTypes() throws Exception {
        IFile mdoFile = mock(IFile.class);
        when(mdoFile.exists()).thenReturn(true);
        IProject project = mockProject(mdoFile);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        MdoFileEditor editor = mock(MdoFileEditor.class);
        when(editor.setConstantType(eq(mdoFile), anyList())).thenReturn(true);

        SetConstantTypeTool tool = new SetConstantTypeTool(() -> root, editor, parser);
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "fqn",     "Constant.Тест",
                "type",    List.of("String(100)", "Number(15,2)")));

        ArgumentCaptor<List> cap = ArgumentCaptor.forClass(List.class);
        verify(editor).setConstantType(eq(mdoFile), cap.capture());
        assertEquals(List.of("String(100)", "Number(15,2)"), cap.getValue());
    }

    @Test(expected = ToolException.class)
    public void rejectsNonConstantFqn() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        SetConstantTypeTool tool = new SetConstantTypeTool(() -> root, mock(MdoFileEditor.class), parser);
        tool.call(Map.of(
                "project", "Demo",
                "fqn",     "Catalog.X",
                "type",    "String(50)"));
    }

    @Test(expected = ToolException.class)
    public void rejectsUnknownType() throws Exception {
        IFile mdoFile = mock(IFile.class);
        when(mdoFile.exists()).thenReturn(true);
        IProject project = mockProject(mdoFile);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        SetConstantTypeTool tool = new SetConstantTypeTool(() -> root, mock(MdoFileEditor.class), parser);
        // BUG-14: <Kind>Ref.<Name> is now parsed generically, so an unknown type
        // must be one that is neither a primitive nor a <Kind>Ref reference.
        tool.call(Map.of(
                "project", "Demo",
                "fqn",     "Constant.Тест",
                "type",    "Frobnicate"));
    }
}
