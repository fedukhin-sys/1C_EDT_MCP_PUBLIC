package ru.fedukhin.edt.mcp.tests.tools.bsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.bsl.ListModuleMethodsTool;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader.MethodInfo;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslParseException;

public class ListModuleMethodsToolTest {

    private static IWorkspaceRoot rootWithBslFile() {
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(true);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/M.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);
        return root;
    }

    @Test
    public void call_mapsMethodInfo() throws Exception {
        BslAstReader reader = mock(BslAstReader.class);
        List<MethodInfo> infos = Arrays.asList(
            new MethodInfo("Proc1", "procedure", true, 5, 10, 100, 200),
            new MethodInfo("Func1", "function", false, 12, 18, 300, 400));
        when(reader.listMethods(any(IFile.class))).thenReturn(infos);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/M.bsl");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>)
            new ListModuleMethodsTool(() -> rootWithBslFile(), reader).call(args);

        assertEquals(2, result.size());
        assertEquals("Proc1", result.get(0).get("name"));
        assertEquals("procedure", result.get(0).get("kind"));
        assertEquals(Boolean.TRUE, result.get(0).get("export"));
        assertEquals(5, result.get(0).get("lineStart"));
        assertEquals(10, result.get(0).get("lineEnd"));
        assertEquals("Func1", result.get(1).get("name"));
        assertEquals("function", result.get(1).get("kind"));
        assertEquals(Boolean.FALSE, result.get(1).get("export"));
    }

    @Test
    public void call_parseErrorThrows() throws Exception {
        BslAstReader reader = mock(BslAstReader.class);
        when(reader.listMethods(any(IFile.class))).thenThrow(new BslParseException("boom"));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/M.bsl");
        try { new ListModuleMethodsTool(() -> rootWithBslFile(), reader).call(args); fail("expected"); }
        catch (ToolException e) { /* ok */ }
    }

    @Test
    public void metadata_isCorrect() {
        ListModuleMethodsTool tool = new ListModuleMethodsTool(() -> mock(IWorkspaceRoot.class), mock(BslAstReader.class));
        assertEquals("list_module_methods", tool.name());
    }
}
