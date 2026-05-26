package ru.fedukhin.edt.mcp.tests.tools.tests;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.tests.GetTestMethodsTool;
import ru.fedukhin.edt.mcp.tools.tests.internal.TestModuleHeuristic;

public class GetTestMethodsToolTest {

    private static IProject projectMock(String name, IWorkspaceRoot root) {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(true);
        when(p.getName()).thenReturn(name);
        when(root.getProject(name)).thenReturn(p);
        return p;
    }

    private static final String RU_MODULE_TEXT = ""
        + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
        + "\tЮнитТесты.ДобавитьТест(\"Тест_ПервыйТест\");\r\n"
        + "КонецПроцедуры\r\n"
        + "\r\n"
        + "Процедура Тест_ПервыйТест() Экспорт\r\n"
        + "\t// test\r\n"
        + "КонецПроцедуры\r\n";

    // Test 1: module with Russian test methods returns correct methods list
    @Test
    public void ruModule_returnsCorrectMethods() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);

        IFile bslFile = mock(IFile.class);
        when(project.getFile("src/CommonModules/КаталогТесты/Module.bsl")).thenReturn(bslFile);
        when(bslFile.exists()).thenReturn(true);
        when(bslFile.getCharset()).thenReturn("UTF-8");
        when(bslFile.getContents()).thenReturn(
                new ByteArrayInputStream(RU_MODULE_TEXT.getBytes("UTF-8")));

        GetTestMethodsTool tool = new GetTestMethodsTool(() -> root, new TestModuleHeuristic());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "moduleFqn", "CommonModule.КаталогТесты"));

        assertEquals("CommonModule.КаталогТесты", result.get("moduleFqn"));
        assertEquals("ru", result.get("language"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) result.get("methods");
        assertEquals(1, methods.size());
        assertEquals("ПервыйТест", methods.get(0).get("name"));
        assertEquals("Тест_ПервыйТест", methods.get(0).get("fqn"));
        assertEquals(true, methods.get(0).get("registered"));
    }

    // Test 2: missing module file throws ToolException
    @Test(expected = ToolException.class)
    public void missingModuleFile_throwsToolException() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IFile bslFile = mock(IFile.class);
        when(project.getFile("src/CommonModules/MyModule/Module.bsl")).thenReturn(bslFile);
        when(bslFile.exists()).thenReturn(false);

        GetTestMethodsTool tool = new GetTestMethodsTool(() -> root, new TestModuleHeuristic());
        tool.call(Map.of("project", "Demo", "moduleFqn", "CommonModule.MyModule"));
    }

    // Test 3: EN module returns methods with registered=true
    @Test
    public void enModule_returnsRegisteredMethods() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);

        String enText = ""
            + "Procedure ExecutableScenarios(UnitTests) Export\r\n"
            + "\tUnitTests.AddTest(\"Test_CreateItem\");\r\n"
            + "EndProcedure\r\n"
            + "\r\n"
            + "Procedure Test_CreateItem() Export\r\n"
            + "\t// test\r\n"
            + "EndProcedure\r\n";

        IFile bslFile = mock(IFile.class);
        when(project.getFile("src/CommonModules/CatalogTests/Module.bsl")).thenReturn(bslFile);
        when(bslFile.exists()).thenReturn(true);
        when(bslFile.getCharset()).thenReturn("UTF-8");
        when(bslFile.getContents()).thenReturn(
                new ByteArrayInputStream(enText.getBytes("UTF-8")));

        GetTestMethodsTool tool = new GetTestMethodsTool(() -> root, new TestModuleHeuristic());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "moduleFqn", "CommonModule.CatalogTests"));

        assertEquals("en", result.get("language"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) result.get("methods");
        assertEquals(1, methods.size());
        assertEquals("CreateItem", methods.get(0).get("name"));
        assertEquals(true, methods.get(0).get("registered"));
    }
}
