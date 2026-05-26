package ru.fedukhin.edt.mcp.tests.tools.bsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.bsl.GetModuleInfoTool;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader.MethodInfo;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslParseException;

public class GetModuleInfoToolTest {

    private static IWorkspaceRoot rootWithBslFile(String projectName, String path) throws Exception {
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(true);
        when(file.getCharset()).thenReturn("UTF-8");
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile(path)).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject(projectName)).thenReturn(project);
        return root;
    }

    @Test
    public void call_returnsCommonModule() throws Exception {
        IWorkspaceRoot root = rootWithBslFile("P", "src/CommonModules/Foo/Module.bsl");
        BslAstReader reader = mock(BslAstReader.class);
        when(reader.getModuleType(org.mockito.ArgumentMatchers.any(IFile.class))).thenReturn("COMMON_MODULE");
        when(reader.listMethods(org.mockito.ArgumentMatchers.any(IFile.class))).thenReturn(java.util.Collections.emptyList());

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P");
        args.put("path", "src/CommonModules/Foo/Module.bsl");
        Map<String, Object> result = new GetModuleInfoTool(() -> root, reader).call(args);

        assertEquals("COMMON_MODULE", result.get("moduleType"));
        assertEquals(0, result.get("methodCount"));
        assertNull(result.get("parseError"));
    }

    @Test
    public void call_returnsFormModuleType() throws Exception {
        IWorkspaceRoot root = rootWithBslFile("P", "src/Catalogs/Foo/Forms/ItemForm/Module.bsl");
        BslAstReader reader = mock(BslAstReader.class);
        when(reader.getModuleType(org.mockito.ArgumentMatchers.any(IFile.class))).thenReturn("FORM_MODULE");
        when(reader.listMethods(org.mockito.ArgumentMatchers.any(IFile.class)))
            .thenReturn(Arrays.asList(new MethodInfo("OnOpen", "procedure", false, 1, 3, 0, 50)));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P");
        args.put("path", "src/Catalogs/Foo/Forms/ItemForm/Module.bsl");
        Map<String, Object> result = new GetModuleInfoTool(() -> root, reader).call(args);

        assertEquals("FORM_MODULE", result.get("moduleType"));
        assertEquals(1, result.get("methodCount"));
    }

    @Test
    public void call_softErrorOnParseFailure() throws Exception {
        IWorkspaceRoot root = rootWithBslFile("P", "src/M.bsl");
        BslAstReader reader = mock(BslAstReader.class);
        when(reader.getModuleType(org.mockito.ArgumentMatchers.any(IFile.class))).thenReturn("UNKNOWN");
        when(reader.listMethods(org.mockito.ArgumentMatchers.any(IFile.class)))
            .thenThrow(new BslParseException("boom"));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/M.bsl");
        Map<String, Object> result = new GetModuleInfoTool(() -> root, reader).call(args);

        // moduleType still resolved via path (no parsing needed); methodCount = null on parse fail.
        assertEquals("UNKNOWN", result.get("moduleType"));
        assertNull(result.get("methodCount"));
        assertEquals("boom", result.get("parseError"));
    }

    @Test(expected = ToolException.class)
    public void call_missingFileThrows() throws Exception {
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(false);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("x.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "x.bsl");
        new GetModuleInfoTool(() -> root, mock(BslAstReader.class)).call(args);
    }

    @Test
    public void metadata_isCorrect() {
        GetModuleInfoTool tool = new GetModuleInfoTool(() -> mock(IWorkspaceRoot.class), mock(BslAstReader.class));
        assertEquals("get_module_info", tool.name());
    }
}
