package ru.fedukhin.edt.mcp.tests.tools.bsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.bsl.ReadModuleTool;

public class ReadModuleToolTest {

    @Test
    public void call_returnsContentAndCharset() throws Exception {
        String content = "Процедура Foo()\n  Сообщить(1);\nКонецПроцедуры\n";
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(true);
        when(file.getCharset()).thenReturn("UTF-8");
        when(file.getContents()).thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));

        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/CommonModules/Foo/Module.bsl")).thenReturn(file);

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P");
        args.put("path", "src/CommonModules/Foo/Module.bsl");
        Map<String, Object> result = new ReadModuleTool(() -> root).call(args);

        assertEquals("P", result.get("project"));
        assertEquals("src/CommonModules/Foo/Module.bsl", result.get("path"));
        assertEquals("UTF-8", result.get("charset"));
        assertEquals(content, result.get("content"));
        assertEquals(4, result.get("lineCount"));
    }

    @Test
    public void call_missingProjectThrows() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Missing")).thenReturn(project);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "Missing"); args.put("path", "x.bsl");
        try { new ReadModuleTool(() -> root).call(args); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_missingFileThrows() throws Exception {
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(false);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/x.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/x.bsl");
        try { new ReadModuleTool(() -> root).call(args); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_nonBslExtensionThrows() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "Configuration.xml");
        try { new ReadModuleTool(() -> root).call(args); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }

    @Test
    public void metadata_isCorrect() {
        ReadModuleTool tool = new ReadModuleTool(() -> mock(IWorkspaceRoot.class));
        assertEquals("read_module", tool.name());
    }
}
