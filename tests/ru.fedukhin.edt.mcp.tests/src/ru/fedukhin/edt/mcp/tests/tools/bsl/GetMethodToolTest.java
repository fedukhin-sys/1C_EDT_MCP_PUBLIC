package ru.fedukhin.edt.mcp.tests.tools.bsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.bsl.GetMethodTool;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader.MethodInfo;

public class GetMethodToolTest {

    @Test
    public void call_returnsMethodTextAndMetadata() throws Exception {
        String content = "Процедура Foo() Экспорт\n  Сообщить(1);\nКонецПроцедуры\n";
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(true);
        when(file.getCharset()).thenReturn("UTF-8");
        when(file.getContents()).thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));

        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/M.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        BslAstReader reader = mock(BslAstReader.class);
        MethodInfo info = new MethodInfo("Foo", "procedure", true, 1, 3, 0, content.length());
        when(reader.findMethod(any(IFile.class), eq("Foo"))).thenReturn(Optional.of(info));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/M.bsl"); args.put("name", "Foo");
        Map<String, Object> result = new GetMethodTool(() -> root, reader).call(args);

        assertEquals("Foo", result.get("name"));
        assertEquals("procedure", result.get("kind"));
        assertEquals(Boolean.TRUE, result.get("export"));
        assertEquals(1, result.get("lineStart"));
        assertEquals(3, result.get("lineEnd"));
        assertEquals(content, result.get("text"));
    }

    @Test
    public void call_methodNotFoundThrows() throws Exception {
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(true);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/M.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);
        BslAstReader reader = mock(BslAstReader.class);
        when(reader.findMethod(any(IFile.class), eq("Missing"))).thenReturn(Optional.empty());

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/M.bsl"); args.put("name", "Missing");
        try { new GetMethodTool(() -> root, reader).call(args); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }

    @Test
    public void metadata_isCorrect() {
        GetMethodTool tool = new GetMethodTool(() -> mock(IWorkspaceRoot.class), mock(BslAstReader.class));
        assertEquals("get_method", tool.name());
    }
}
