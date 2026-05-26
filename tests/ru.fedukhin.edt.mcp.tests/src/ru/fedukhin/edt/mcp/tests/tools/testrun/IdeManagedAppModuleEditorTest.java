package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.testrun.internal.BslRunnerTemplates;
import ru.fedukhin.edt.mcp.tools.testrun.internal.IdeManagedAppModuleEditor;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller;

/**
 * Unit-тесты для {@link IdeManagedAppModuleEditor}.
 * Используют реальное файловое I/O в temp-директории; IProject имитируется через Mockito.
 */
public class IdeManagedAppModuleEditorTest {

    private Path tempProjectRoot;
    private IProject project;
    private IdeManagedAppModuleEditor editor;

    @Before
    public void setUp() throws Exception {
        tempProjectRoot = Path.of(System.getProperty("java.io.tmpdir"),
                "edt-mcp-editor-test-" + UUID.randomUUID());
        Files.createDirectories(tempProjectRoot);

        IPath ipath = mock(IPath.class);
        when(ipath.toOSString()).thenReturn(tempProjectRoot.toString());

        project = mock(IProject.class);
        when(project.getLocation()).thenReturn(ipath);

        editor = new IdeManagedAppModuleEditor();
    }

    @After
    public void tearDown() throws Exception {
        if (tempProjectRoot != null && Files.exists(tempProjectRoot)) {
            Files.walk(tempProjectRoot)
                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    public void hasMarker_fileMissing_returnsFalse() {
        assertFalse("no file → hasMarker returns false", editor.hasMarker(project));
    }

    @Test
    public void appendConfigurationHandler_createsFile_withMarkerBlock() throws Exception {
        editor.appendConfigurationHandler(project);

        Path file = tempProjectRoot.resolve("src/Configuration/ManagedApplicationModule.bsl");
        assertTrue("file must be created", Files.exists(file));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue("must contain MARKER_BEGIN", text.contains(TestRunnerInstaller.MARKER_BEGIN));
        assertTrue("must contain MARKER_END",   text.contains(TestRunnerInstaller.MARKER_END));
        assertTrue("must contain handler procedure name",
                text.contains("Процедура ПриНачалеРаботыСистемы()"));
        assertTrue("must call client module",
                text.contains("EDT_MCP_TestRunner_Клиент"));
        assertTrue("hasMarker should now return true", editor.hasMarker(project));
    }

    @Test
    public void appendExtensionHandler_usesAfterAnnotation() throws Exception {
        editor.appendExtensionHandler(project);

        Path file = tempProjectRoot.resolve("src/Configuration/ManagedApplicationModule.bsl");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue("extension handler must use &После annotation",
                text.contains("&После(\"ПриНачалеРаботыСистемы\")"));
    }

    @Test
    public void append_isIdempotent_secondCallNoOp() throws Exception {
        editor.appendConfigurationHandler(project);
        Path file = tempProjectRoot.resolve("src/Configuration/ManagedApplicationModule.bsl");
        long sizeAfter1 = Files.size(file);

        editor.appendConfigurationHandler(project);
        assertEquals("second append must be a no-op", sizeAfter1, Files.size(file));
    }

    @Test
    public void removeMarkerBlock_removesOurInsertion_keepsExistingContent() throws Exception {
        Path file = tempProjectRoot.resolve("src/Configuration/ManagedApplicationModule.bsl");
        Files.createDirectories(file.getParent());
        String preExistingUserCode =
                "Процедура ПриНачалеРаботыСистемы()\r\n\t// user code\r\nКонецПроцедуры\r\n";
        Files.writeString(file, preExistingUserCode, StandardCharsets.UTF_8);

        editor.appendConfigurationHandler(project);
        editor.removeMarkerBlock(project);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue("user code must be preserved", text.contains("// user code"));
        assertFalse("marker block must be removed", text.contains(TestRunnerInstaller.MARKER_BEGIN));
    }

    @Test
    public void removeMarkerBlock_notInstalled_noOp() throws Exception {
        // нет файла → silent no-op
        editor.removeMarkerBlock(project);

        // файл без наших маркеров → тоже no-op
        Path file = tempProjectRoot.resolve("src/Configuration/ManagedApplicationModule.bsl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "// user only\r\n", StandardCharsets.UTF_8);
        editor.removeMarkerBlock(project);
        assertEquals("// user only\r\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    public void handlerBody_configurationMatchesTemplates() throws Exception {
        // Проверяем, что тело обработчика совпадает с BslRunnerTemplates
        editor.appendConfigurationHandler(project);
        Path file = tempProjectRoot.resolve("src/Configuration/ManagedApplicationModule.bsl");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String expected = BslRunnerTemplates.handlerProcedureForConfiguration();
        assertTrue("handler body from templates must appear in file", text.contains(expected));
    }
}
