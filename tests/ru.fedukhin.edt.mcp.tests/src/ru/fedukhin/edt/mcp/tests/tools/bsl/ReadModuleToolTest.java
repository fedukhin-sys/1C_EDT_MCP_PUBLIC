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

    /**
     * 1С пишет .bsl с BOM. Читая файл как UTF-8, BOM приезжает первым символом контента
     * (U+FEFF) — он ломает всё, что сравнивает или парсит первую строку модуля.
     */
    @Test
    public void call_utf8Bom_isStrippedFromContent() throws Exception {
        String content = "Процедура Foo()\nКонецПроцедуры\n";
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(body, 0, withBom, bom.length, body.length);

        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(true);
        when(file.getCharset()).thenReturn("UTF-8");
        when(file.getContents()).thenReturn(new ByteArrayInputStream(withBom));

        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/CommonModules/Foo/Module.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P");
        args.put("path", "src/CommonModules/Foo/Module.bsl");
        Map<String, Object> result = new ReadModuleTool(() -> root).call(args);

        assertEquals(content, result.get("content"));
        assertEquals("BOM должен быть отмечен отдельным полем, а не приезжать в content",
                Boolean.TRUE, result.get("hasBom"));
    }

    @Test
    public void call_noBom_hasBomIsFalse() throws Exception {
        String content = "Процедура Foo()\nКонецПроцедуры\n";
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(true);
        when(file.getCharset()).thenReturn("UTF-8");
        when(file.getContents()).thenReturn(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));

        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/CommonModules/Foo/Module.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P");
        args.put("path", "src/CommonModules/Foo/Module.bsl");
        Map<String, Object> result = new ReadModuleTool(() -> root).call(args);

        assertEquals(content, result.get("content"));
        assertEquals(Boolean.FALSE, result.get("hasBom"));
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
