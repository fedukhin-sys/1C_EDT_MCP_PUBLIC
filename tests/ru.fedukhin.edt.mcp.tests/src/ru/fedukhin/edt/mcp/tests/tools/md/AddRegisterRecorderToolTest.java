package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.AddRegisterRecorderTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * Bug #1 v1.10.2: проверяет, что add_register_recorder вызывает оба write'а
 * (recorders в register .mdo + registerRecords в document .mdo) и
 * корректно валидирует входы.
 */
public class AddRegisterRecorderToolTest {

    private IProject mockProject(IFile regMdo, IFile docMdo) {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        when(project.getFile("src/AccumulationRegisters/Sales/Sales.mdo")).thenReturn(regMdo);
        when(project.getFile("src/Documents/Order/Order.mdo")).thenReturn(docMdo);
        return project;
    }

    @Test
    public void linksDocumentToAccReg() throws Exception {
        IFile regMdo = mock(IFile.class);
        IFile docMdo = mock(IFile.class);
        when(regMdo.exists()).thenReturn(true);
        when(docMdo.exists()).thenReturn(true);
        IProject project = mockProject(regMdo, docMdo);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        MdoFileEditor mdoEditor = mock(MdoFileEditor.class);
        when(mdoEditor.addRecorder(eq(regMdo), eq("Document.Order"))).thenReturn(true);
        when(mdoEditor.addRegisterRecord(eq(docMdo), eq("AccumulationRegister.Sales"))).thenReturn(true);

        AddRegisterRecorderTool tool = new AddRegisterRecorderTool(() -> root, mdoEditor);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project",  "Demo",
                "register", "AccumulationRegister.Sales",
                "document", "Document.Order"));

        assertEquals("AccumulationRegister.Sales", result.get("register"));
        assertEquals("Document.Order",             result.get("document"));
        assertEquals(true, result.get("recorderAdded"));
        assertEquals(true, result.get("registerRecordAdded"));

        verify(mdoEditor).addRecorder(regMdo, "Document.Order");
        verify(mdoEditor).addRegisterRecord(docMdo, "AccumulationRegister.Sales");
    }

    @Test
    public void idempotent_reportsNoChanges() throws Exception {
        IFile regMdo = mock(IFile.class);
        IFile docMdo = mock(IFile.class);
        when(regMdo.exists()).thenReturn(true);
        when(docMdo.exists()).thenReturn(true);
        IProject project = mockProject(regMdo, docMdo);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        MdoFileEditor mdoEditor = mock(MdoFileEditor.class);
        // Both already exist → MdoFileEditor returns false → tool reports false too
        when(mdoEditor.addRecorder(any(), any())).thenReturn(false);
        when(mdoEditor.addRegisterRecord(any(), any())).thenReturn(false);

        AddRegisterRecorderTool tool = new AddRegisterRecorderTool(() -> root, mdoEditor);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project",  "Demo",
                "register", "AccumulationRegister.Sales",
                "document", "Document.Order"));

        assertEquals(false, result.get("recorderAdded"));
        assertEquals(false, result.get("registerRecordAdded"));
    }

    @Test(expected = ToolException.class)
    public void rejectsCatalogAsDocument() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        AddRegisterRecorderTool tool = new AddRegisterRecorderTool(() -> root, mock(MdoFileEditor.class));
        tool.call(Map.of(
                "project",  "Demo",
                "register", "AccumulationRegister.Sales",
                "document", "Catalog.Goods")); // not a Document
    }

    @Test(expected = ToolException.class)
    public void rejectsNonRegister() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        AddRegisterRecorderTool tool = new AddRegisterRecorderTool(() -> root, mock(MdoFileEditor.class));
        tool.call(Map.of(
                "project",  "Demo",
                "register", "Catalog.Goods", // not a register
                "document", "Document.Order"));
    }
}
