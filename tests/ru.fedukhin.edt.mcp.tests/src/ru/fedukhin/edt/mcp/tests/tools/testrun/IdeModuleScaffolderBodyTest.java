package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fedukhin.edt.mcp.tools.testrun.internal.IdeModuleScaffolder;

/**
 * Чтение и перезапись текста модуля раннера — основа обновления раннера повторным
 * {@code install_test_runner}. Реальное файловое I/O во временном каталоге, IProject — мок.
 */
public class IdeModuleScaffolderBodyTest {

    private static final String NAME = "EDT_MCP_TestRunner_Клиент_Demo";
    private static final String BOM = String.valueOf((char) 0xFEFF);

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private IProject project;
    private IdeModuleScaffolder scaffolder;

    @Before
    public void setUp() throws Exception {
        root = tmp.newFolder("project").toPath();
        IPath location = mock(IPath.class);
        when(location.toOSString()).thenReturn(root.toString());
        project = mock(IProject.class);
        when(project.getLocation()).thenReturn(location);
        // BM-зависимости чтению и перезаписи текста не нужны.
        scaffolder = new IdeModuleScaffolder(null, null, null, null, null);
    }

    private Path moduleFile() {
        return root.resolve("src").resolve("CommonModules").resolve(NAME).resolve("Module.bsl");
    }

    @Test
    public void readModuleBody_noModule_returnsNull() {
        assertNull(scaffolder.readModuleBody(project, NAME));
    }

    @Test
    public void readModuleBody_stripsBom() throws Exception {
        Files.createDirectories(moduleFile().getParent());
        Files.writeString(moduleFile(), BOM + "Процедура А()\r\nКонецПроцедуры\r\n", StandardCharsets.UTF_8);
        assertEquals("Процедура А()\r\nКонецПроцедуры\r\n", scaffolder.readModuleBody(project, NAME));
    }

    /** EDT сохраняет .bsl с BOM — перезапись не должна менять форму файла. */
    @Test
    public void writeModuleBody_keepsBomOfExistingFile() throws Exception {
        Files.createDirectories(moduleFile().getParent());
        Files.writeString(moduleFile(), BOM + "старое\r\n", StandardCharsets.UTF_8);

        scaffolder.writeModuleBody(project, NAME, "новое\r\n");

        byte[] bytes = Files.readAllBytes(moduleFile());
        assertArrayEquals("BOM сохранён", new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
            Arrays.copyOf(bytes, 3));
        assertEquals("новое\r\n", scaffolder.readModuleBody(project, NAME));
    }

    @Test
    public void writeModuleBody_fileWithoutBom_staysWithoutBom() throws Exception {
        Files.createDirectories(moduleFile().getParent());
        Files.writeString(moduleFile(), "старое\r\n", StandardCharsets.UTF_8);

        scaffolder.writeModuleBody(project, NAME, "новое\r\n");

        assertEquals("новое\r\n", Files.readString(moduleFile(), StandardCharsets.UTF_8));
    }
}
