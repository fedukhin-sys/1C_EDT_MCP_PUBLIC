package ru.fedukhin.edt.mcp.tests.tools.tests;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
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

    private IFile makeProject(IWorkspaceRoot root, String moduleName, String content)
            throws Exception {
        return makeModuleFile(root, moduleName, content.getBytes(StandardCharsets.UTF_8), "UTF-8");
    }

    /** Мок проекта «Demo» с файлом модуля; возвращает мок файла — через него читается записанный текст. */
    private IFile makeModuleFile(IWorkspaceRoot root, String moduleName, byte[] content, String charset)
            throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        when(root.getProject("Demo")).thenReturn(project);

        IFile bslFile = mock(IFile.class);
        when(project.getFile("src/CommonModules/" + moduleName + "/Module.bsl")).thenReturn(bslFile);
        when(bslFile.exists()).thenReturn(true);
        when(bslFile.getCharset()).thenReturn(charset);
        when(bslFile.getContents()).thenReturn(new ByteArrayInputStream(content));
        return bslFile;
    }

    /** Байты, которые инструмент записал в файл модуля. */
    private static byte[] writtenBytes(IFile bslFile) throws Exception {
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(bslFile).setContents(captor.capture(), anyBoolean(), anyBoolean(), any());
        return captor.getValue().readAllBytes();
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

    // ---- Регрессии 2026-10-08 (CommonModule.ДемоТест_Закупки) ----

    // Имя уже с префиксом Тест_: в модуль должна попасть процедура ровно с тем именем,
    // которое инструмент вернул в fqn — иначе run_test_method по этому fqn падает
    // «Метод объекта не обнаружен».
    @Test
    public void prefixedMethodName_writtenProcedureMatchesReportedFqn() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IFile bslFile = makeProject(root, "ДемоТест_Закупки", RU_MODULE);

        AddTestMethodTool tool = new AddTestMethodTool(
                () -> root, new TestModuleHeuristic(), new BslTestMethodAppender());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "moduleFqn", "CommonModule.ДемоТест_Закупки",
                "methodName", "Тест_Закупки_Диагностика2",
                "body", "\tЛог(\"a\");"));

        String written = new String(writtenBytes(bslFile), StandardCharsets.UTF_8);
        assertEquals("Тест_Закупки_Диагностика2", result.get("fqn"));
        assertEquals(true, result.get("registered"));
        assertTrue("процедура должна называться как fqn и быть экспортной",
                written.contains("Процедура Тест_Закупки_Диагностика2() Экспорт\r\n"));
        assertTrue(written.contains("ЮнитТесты.ДобавитьТест(\"Тест_Закупки_Диагностика2\");"));
        assertFalse("префикс не должен удваиваться", written.contains("Тест_Тест_"));
        assertTrue("КонецПроцедуры — на своей строке",
                written.contains("\tЛог(\"a\");\r\nКонецПроцедуры\r\n"));
        assertTrue("метод — внутри области, а не после последнего #КонецОбласти",
                written.indexOf("Процедура Тест_Закупки_Диагностика2()") < written.lastIndexOf("#КонецОбласти"));
    }

    @Test
    public void utf8BomModule_bomPreservedOnceOnWrite() throws Exception {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = RU_MODULE.getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, content, 0, bom.length);
        System.arraycopy(body, 0, content, bom.length, body.length);

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IFile bslFile = makeModuleFile(root, "КаталогТесты", content, "UTF-8");
        AddTestMethodTool tool = new AddTestMethodTool(
                () -> root, new TestModuleHeuristic(), new BslTestMethodAppender());

        tool.call(Map.of("project", "Demo",
                "moduleFqn", "CommonModule.КаталогТесты",
                "methodName", "Новый"));

        byte[] written = writtenBytes(bslFile);
        assertArrayEquals("BOM должен сохраниться", bom, Arrays.copyOf(written, 3));
        String text = new String(written, StandardCharsets.UTF_8);
        assertEquals("BOM ровно один", text.indexOf(0xFEFF), text.lastIndexOf(0xFEFF));
        assertTrue("с BOM метод всё равно вставлен внутрь области",
                text.indexOf("Процедура Тест_Новый() Экспорт") < text.indexOf("#КонецОбласти"));
    }

    @Test
    public void lfModule_writtenWithLfOnly() throws Exception {
        String lfModule = RU_MODULE.replace("\r\n", "\n");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IFile bslFile = makeProject(root, "КаталогТесты", lfModule);
        AddTestMethodTool tool = new AddTestMethodTool(
                () -> root, new TestModuleHeuristic(), new BslTestMethodAppender());

        tool.call(Map.of("project", "Demo",
                "moduleFqn", "CommonModule.КаталогТесты",
                "methodName", "Новый",
                "body", "\tЛог(\"a\");\r\n"));

        String text = new String(writtenBytes(bslFile), StandardCharsets.UTF_8);
        assertFalse("в LF-модуле не должно появиться \\r", text.contains("\r"));
        assertTrue(text.contains("Процедура Тест_Новый() Экспорт\n\tЛог(\"a\");\nКонецПроцедуры\n"));
    }

    @Test
    public void nonUtf8Module_writtenInTheSameCharset() throws Exception {
        Charset cp1251 = Charset.forName("windows-1251");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IFile bslFile = makeModuleFile(root, "КаталогТесты", RU_MODULE.getBytes(cp1251), "windows-1251");
        AddTestMethodTool tool = new AddTestMethodTool(
                () -> root, new TestModuleHeuristic(), new BslTestMethodAppender());

        tool.call(Map.of("project", "Demo",
                "moduleFqn", "CommonModule.КаталогТесты",
                "methodName", "Новый"));

        String text = new String(writtenBytes(bslFile), cp1251);
        assertTrue("файл должен быть записан в кодировке, в которой прочитан",
                text.contains("Процедура Тест_Новый() Экспорт"));
    }
}
