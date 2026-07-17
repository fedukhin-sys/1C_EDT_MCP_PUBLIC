package ru.fedukhin.edt.mcp.tests.tools.tests;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.tests.AddTestMethodTool;
import ru.fedukhin.edt.mcp.tools.tests.internal.BslTestMethodAppender;
import ru.fedukhin.edt.mcp.tools.tests.internal.TestModuleHeuristic;

public class AddTestMethodToolTest {

    private static final String RU_MODULE = ""
        + "#Область ПрограммныйИнтерфейс\r\n\r\n"
        + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
        + "КонецПроцедуры\r\n\r\n"
        + "#КонецОбласти\r\n";

    private IProject makeProject(IWorkspaceRoot root, String moduleName, String content)
            throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        when(root.getProject("Demo")).thenReturn(project);

        IFile bslFile = mock(IFile.class);
        when(project.getFile("src/CommonModules/" + moduleName + "/Module.bsl")).thenReturn(bslFile);
        when(bslFile.exists()).thenReturn(true);
        when(bslFile.getCharset()).thenReturn("UTF-8");
        when(bslFile.getContents()).thenReturn(
                new ByteArrayInputStream(content.getBytes("UTF-8")));
        return project;
    }

    // Test 1: happy-path append — alreadyExisted=false, fqn has Тест_ prefix
    @Test
    public void happyPathAppend_returnsFqnAndRegistered() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        makeProject(root, "КаталогТесты", RU_MODULE);

        AddTestMethodTool tool = new AddTestMethodTool(
                () -> root, new TestModuleHeuristic(), new BslTestMethodAppender());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "moduleFqn", "CommonModule.КаталогТесты",
                "methodName", "СозданиеСправочника"));

        assertEquals("CommonModule.КаталогТесты", result.get("moduleFqn"));
        assertEquals("Тест_СозданиеСправочника", result.get("fqn"));
        assertEquals(true, result.get("registered"));
        assertEquals(false, result.get("alreadyExisted"));
    }

    // Test 2: idempotent re-add — alreadyExisted=true
    @Test
    public void idempotentReAdd_alreadyExistedTrue() throws Exception {
        String moduleWithMethod = RU_MODULE
                + "\r\nПроцедура Тест_МойТест() Экспорт\r\n\t// TODO\r\nКонецПроцедуры\r\n";
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        makeProject(root, "КаталогТесты", moduleWithMethod);

        AddTestMethodTool tool = new AddTestMethodTool(
                () -> root, new TestModuleHeuristic(), new BslTestMethodAppender());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "moduleFqn", "CommonModule.КаталогТесты",
                "methodName", "МойТест"));

        assertEquals(true, result.get("alreadyExisted"));
        assertEquals("Тест_МойТест", result.get("fqn"));
    }

    // Модуль-«тестовый» без процедуры ИсполняемыеСценарии: метод дописать можно,
    // но зарегистрировать его негде — xUnitFor1C такой тест не увидит.
    private static final String RU_MODULE_NO_SCENARIOS = ""
        + "#Область ПрограммныйИнтерфейс\r\n\r\n"
        + "Процедура Тест_Существующий() Экспорт\r\n"
        + "КонецПроцедуры\r\n\r\n"
        + "#КонецОбласти\r\n";

    @Test
    public void moduleWithoutExecutableScenarios_reportsRegisteredFalseWithWarning() throws Exception {
        // Без ИсполняемыеСценарии строка ЮнитТесты.ДобавитьТест(...) не вставляется —
        // метод есть в модуле, но раннер его никогда не запустит. Рапортовать
        // registered:true в этом случае = врать клиенту.
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        makeProject(root, "КаталогТесты", RU_MODULE_NO_SCENARIOS);

        AddTestMethodTool tool = new AddTestMethodTool(
                () -> root, new TestModuleHeuristic(), new BslTestMethodAppender());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "moduleFqn", "CommonModule.КаталогТесты",
                "methodName", "НовыйТест"));

        assertEquals(false, result.get("registered"));
        assertEquals(false, result.get("alreadyExisted"));
        assertNotNull("должно быть предупреждение о пропущенной регистрации",
                result.get("warning"));
    }

    @Test
    public void existingMethodWithoutRegistration_reportsRegisteredFalse() throws Exception {
        // Идемпотентный повтор по модулю, где метод есть, а регистрации нет:
        // alreadyExisted=true не должен маскировать отсутствие регистрации.
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        makeProject(root, "КаталогТесты", RU_MODULE_NO_SCENARIOS);

        AddTestMethodTool tool = new AddTestMethodTool(
                () -> root, new TestModuleHeuristic(), new BslTestMethodAppender());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "moduleFqn", "CommonModule.КаталогТесты",
                "methodName", "Существующий"));

        assertEquals(true, result.get("alreadyExisted"));
        assertEquals(false, result.get("registered"));
    }

    @Test
    public void existingMethodWithRegistration_reportsRegisteredTrue() throws Exception {
        String registered = ""
            + "#Область ПрограммныйИнтерфейс\r\n\r\n"
            + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
            + "\tЮнитТесты.ДобавитьТест(\"Тест_МойТест\");\r\n"
            + "КонецПроцедуры\r\n\r\n"
            + "Процедура Тест_МойТест() Экспорт\r\n"
            + "КонецПроцедуры\r\n";
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        makeProject(root, "КаталогТесты", registered);

        AddTestMethodTool tool = new AddTestMethodTool(
                () -> root, new TestModuleHeuristic(), new BslTestMethodAppender());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "moduleFqn", "CommonModule.КаталогТесты",
                "methodName", "МойТест"));

        assertEquals(true, result.get("alreadyExisted"));
        assertEquals(true, result.get("registered"));
        assertNull(result.get("warning"));
    }

    // Test 3: body parameter embedded in method — verify result is not alreadyExisted
    @Test
    public void bodyParam_embeddedInMethod() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        makeProject(root, "КаталогТесты", RU_MODULE);

        AddTestMethodTool tool = new AddTestMethodTool(
                () -> root, new TestModuleHeuristic(), new BslTestMethodAppender());

        String customBody = "\tAssertEquals(42, 42);\r\n";
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "moduleFqn", "CommonModule.КаталогТесты",
                "methodName", "ПроверкаЧисла",
                "body", customBody));

        // alreadyExisted is false; fqn should include Тест_ prefix
        assertEquals(false, result.get("alreadyExisted"));
        assertEquals("Тест_ПроверкаЧисла", result.get("fqn"));
    }
}
