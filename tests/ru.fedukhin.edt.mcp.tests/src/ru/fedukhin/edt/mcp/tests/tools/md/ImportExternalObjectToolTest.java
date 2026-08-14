package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.import_.IImportOperation;
import com._1c.g5.v8.dt.import_.IImportOperationFactory;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectRestorer;
import com._1c.g5.v8.dt.platform.version.Version;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.ImportExternalObjectTool;
import ru.fedukhin.edt.mcp.tools.md.internal.ExternalObjectImporter;

/**
 * {@code import_external_object} — импорт готового .epf/.erf в проект внешних объектов тем же
 * путём, что мастер импорта в IDE: {@link IExternalObjectRestorer} распаковывает файл в
 * Designer-XML, {@link IImportOperationFactory} импортирует XML в исходники EDT.
 *
 * <p>Оба сервиса подменены сеймами: тесты проверяют оркестрацию (имя объекта из содержимого
 * файла, защита от перезаписи, разбор статуса, уборка временного каталога), а не работу EDT.
 */
public class ImportExternalObjectToolTest {

    private static final String PROJECT_NAME = "ВнешниеОбработки";

    /** Корневой XML выгрузки: вид объекта в корневом теге, имя — в Properties/Name. */
    private static final String ROOT_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MetaDataObject xmlns="http://v8.1c.ru/8.3/MDClasses" version="2.21">
            <ExternalDataProcessor uuid="fc3145a3-afab-42ca-a2e4-ceb2c30628af">
                <Properties>
                    <Name>АРМ</Name>
                    <Synonym/>
                </Properties>
                <ChildObjects>
                    <Form>
                        <Name>Форма</Name>
                    </Form>
                </ChildObjects>
            </ExternalDataProcessor>
        </MetaDataObject>
        """;

    private IExternalObjectRestorer restorer;
    private IImportOperationFactory factory;
    private IImportOperation        operation;
    private Path                    epfFile;
    private Path                    capturedTempDir;
    private IFolder                 objectFolder;
    private IFile                   mdoFile;

    /**
     * Инструмент на моках.
     *
     * @param unpacked   что «распакует» restore: {@code null} — не создавать XML вовсе
     * @param collision  существует ли уже каталог объекта в проекте
     * @param importedOk появится ли .mdo после импорта
     * @param status     статус операции импорта
     */
    private ImportExternalObjectTool toolFor(String unpacked, boolean collision,
                                             boolean importedOk, IStatus status) throws Exception {
        epfFile = Files.createTempDirectory("edt-mcp-test-epf-").resolve("АРМ_150626.epf");
        Files.writeString(epfFile, "epf-bytes");

        restorer = mock(IExternalObjectRestorer.class);
        doAnswer(inv -> {
            capturedTempDir = inv.getArgument(2);
            if (unpacked != null) {
                // Платформа кладёт <корень>.xml рядом с каталогом <корень>/.
                Files.writeString(capturedTempDir.resolve("АРМ_150626.xml"), unpacked);
                Files.createDirectories(capturedTempDir.resolve("АРМ_150626"));
            }
            return null;
        }).when(restorer).restore(any(), any(), any(), any());

        operation = mock(IImportOperation.class);
        when(operation.getStatus()).thenReturn(status);
        doAnswer(inv -> {
            // Импорт «создал» исходники: дальше инструмент верит только файлу на диске.
            org.mockito.Mockito.doReturn(importedOk).when(mdoFile).exists();
            return null;
        }).when(operation).run(any());

        factory = mock(IImportOperationFactory.class);
        when(factory.createImportExternalObjectOperation(anyString(), any(), any(), any()))
            .thenReturn(operation);

        IWorkspaceRoot root = root(collision);
        ExternalObjectImporter importer = new ExternalObjectImporter(restorer, factory,
            project -> new ExternalObjectImporter.ProjectContext(
                Version.V8_5_1, mock(IConfigurationProject.class)));
        return new ImportExternalObjectTool(() -> root, () -> importer);
    }

    private IWorkspaceRoot root(boolean collision) {
        objectFolder = mock(IFolder.class);
        when(objectFolder.exists()).thenReturn(collision);

        mdoFile = mock(IFile.class);
        when(mdoFile.exists()).thenReturn(false);

        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn(PROJECT_NAME);
        when(project.getFolder("src/ExternalDataProcessors/АРМ")).thenReturn(objectFolder);
        when(project.getFile("src/ExternalDataProcessors/АРМ/АРМ.mdo")).thenReturn(mdoFile);

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject(PROJECT_NAME)).thenReturn(project);
        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(ImportExternalObjectTool tool, Object... extraArgs)
            throws Exception {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("project", PROJECT_NAME);
        args.put("file",    epfFile.toString());
        for (int i = 0; i < extraArgs.length; i += 2) {
            args.put((String) extraArgs[i], extraArgs[i + 1]);
        }
        return (Map<String, Object>) tool.call(args);
    }

    // --- happy path -------------------------------------------------------

    /** Имя объекта берётся из содержимого файла: АРМ_150626.epf → ExternalDataProcessor.АРМ. */
    @Test
    public void importsEpf_objectNameComesFromFileContent() throws Exception {
        ImportExternalObjectTool tool = toolFor(ROOT_XML, false, true, Status.OK_STATUS);

        Map<String, Object> result = call(tool);

        assertEquals("ExternalDataProcessor.АРМ", result.get("fqn"));
        assertEquals("src/ExternalDataProcessors/АРМ", result.get("objectDir"));
        assertEquals(PROJECT_NAME, result.get("project"));
        assertTrue(result.get("warnings").toString(), ((List<?>) result.get("warnings")).isEmpty());

        verify(restorer).restore(any(IProject.class), eq(epfFile), any(Path.class),
            any(IProgressMonitor.class));
        verify(operation).setRefreshProject(true);
    }

    /**
     * Фабрика импорта получает корень выгрузки без расширения ({@code tmp/<имя файла>}) —
     * именно так считает мастер IDE, а платформа рядом кладёт {@code <корень>.xml}.
     */
    @Test
    public void passesExtensionlessDumpRoot_toImportFactory() throws Exception {
        ImportExternalObjectTool tool = toolFor(ROOT_XML, false, true, Status.OK_STATUS);
        call(tool);

        verify(factory).createImportExternalObjectOperation(eq(PROJECT_NAME), eq(Version.V8_5_1),
            eq(capturedTempDir.resolve("АРМ_150626")), any(IConfigurationProject.class));
    }

    /** Временный каталог живёт только на время импорта. */
    @Test
    public void removesTempDirectory_onSuccess() throws Exception {
        call(toolFor(ROOT_XML, false, true, Status.OK_STATUS));

        assertNotNull(capturedTempDir);
        assertFalse("временный каталог обязан убираться", Files.exists(capturedTempDir));
    }

    @Test
    public void removesTempDirectory_onFailure() throws Exception {
        ImportExternalObjectTool tool = toolFor(ROOT_XML, false, false, Status.OK_STATUS);

        try {
            call(tool);
            fail("исходники не появились — обязана быть ошибка");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("АРМ.mdo"));
        }
        assertFalse("временный каталог обязан убираться и на отказе", Files.exists(capturedTempDir));
    }

    // --- защита от перезаписи ---------------------------------------------

    /**
     * Имя объекта известно только после распаковки, поэтому коллизия ловится здесь — но до
     * импорта: EDT перезаписал бы исходники молча (в IDE на этом месте диалог с вопросом).
     */
    @Test
    public void existingObject_withoutOverwrite_isRefusedBeforeImport() throws Exception {
        ImportExternalObjectTool tool = toolFor(ROOT_XML, true, true, Status.OK_STATUS);

        try {
            call(tool);
            fail("объект с таким именем уже есть — импорт обязан отбиваться");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("уже есть"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("overwrite=true"));
        }

        verify(factory, never()).createImportExternalObjectOperation(anyString(), any(), any(), any());
    }

    @Test
    public void existingObject_withOverwrite_isImported() throws Exception {
        ImportExternalObjectTool tool = toolFor(ROOT_XML, true, true, Status.OK_STATUS);

        Map<String, Object> result = call(tool, "overwrite", Boolean.TRUE);

        assertEquals("ExternalDataProcessor.АРМ", result.get("fqn"));
        verify(operation).run(any(IProgressMonitor.class));
    }

    // --- статус операции --------------------------------------------------

    @Test
    public void errorStatus_failsWithEdtMessage() throws Exception {
        IStatus error = new Status(IStatus.ERROR, "com._1c.g5.v8.dt.import",
            "Illegal project type");
        ImportExternalObjectTool tool = toolFor(ROOT_XML, false, true, error);

        try {
            call(tool);
            fail("статус ERROR обязан приводить к ошибке инструмента");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Illegal project type"));
        }
    }

    @Test
    public void warningStatus_landsInWarnings() throws Exception {
        IStatus warning = new Status(IStatus.WARNING, "com._1c.g5.v8.dt.import",
            "объект импортирован с замечаниями");
        ImportExternalObjectTool tool = toolFor(ROOT_XML, false, true, warning);

        Map<String, Object> result = call(tool);

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertTrue(warnings.toString(),
            warnings.stream().anyMatch(w -> w.contains("с замечаниями")));
    }

    // --- транзиентные отказы ----------------------------------------------

    /**
     * Первое обращение к ИБ проекта в свежем сеансе EDT падает «already connected»; повтор
     * уже использует готовое подключение. Тот же класс отказа, что при сборке .epf.
     */
    @Test
    public void alreadyConnected_isRetriedOnce() throws Exception {
        ImportExternalObjectTool tool = toolFor(ROOT_XML, false, true, Status.OK_STATUS);

        boolean[] firstCall = { true };
        doAnswer(inv -> {
            capturedTempDir = inv.getArgument(2);
            if (firstCall[0]) {
                firstCall[0] = false;
                throw new IllegalArgumentException("Infobase Демо is already connected");
            }
            Files.writeString(capturedTempDir.resolve("АРМ_150626.xml"), ROOT_XML);
            return null;
        }).when(restorer).restore(any(), any(), any(), any());

        Map<String, Object> result = call(tool);

        assertEquals("ExternalDataProcessor.АРМ", result.get("fqn"));
        verify(restorer, org.mockito.Mockito.times(2))
            .restore(any(), any(), any(), any());
    }

    // --- разбор распакованного --------------------------------------------

    @Test
    public void missingRootXml_failsWithClearMessage() throws Exception {
        ImportExternalObjectTool tool = toolFor(null, false, true, Status.OK_STATUS);

        try {
            call(tool);
            fail("без корневого XML импортировать нечего");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(),
                expected.getMessage().contains("корневого XML"));
        }
    }

    /** Конфигурацию или расширение этим путём импортировать нельзя — только внешние объекты. */
    @Test
    public void foreignRootTag_isRejected() throws Exception {
        String configurationXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MetaDataObject xmlns="http://v8.1c.ru/8.3/MDClasses" version="2.21">
                <Configuration uuid="0f2a1b3c-0000-0000-0000-000000000000">
                    <Properties><Name>Демо</Name></Properties>
                </Configuration>
            </MetaDataObject>
            """;
        ImportExternalObjectTool tool = toolFor(configurationXml, false, true, Status.OK_STATUS);

        try {
            call(tool);
            fail("корневой тег не ExternalDataProcessor/ExternalReport — импорт обязан отбиваться");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(),
                expected.getMessage().contains("ExternalDataProcessor"));
        }
    }

    // --- валидация аргументов ---------------------------------------------

    @Test
    public void rejectsNonExternalObjectFile() throws Exception {
        ImportExternalObjectTool tool = toolFor(ROOT_XML, false, true, Status.OK_STATUS);
        Path cf = epfFile.resolveSibling("Конфигурация.cf");
        Files.writeString(cf, "cf-bytes");

        try {
            call(tool, "file", cf.toString());
            fail(".cf не является внешним объектом");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(".epf or .erf"));
        }
        verify(restorer, never()).restore(any(), any(), any(), any());
    }

    @Test
    public void rejectsMissingFile() throws Exception {
        ImportExternalObjectTool tool = toolFor(ROOT_XML, false, true, Status.OK_STATUS);

        try {
            call(tool, "file", epfFile.resolveSibling("Нет.epf").toString());
            fail("файла нет — импортировать нечего");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("file not found"));
        }
    }

    @Test
    public void rejectsTimeoutOutOfRange() throws Exception {
        ImportExternalObjectTool tool = toolFor(ROOT_XML, false, true, Status.OK_STATUS);

        try {
            call(tool, "timeoutSeconds", 5);
            fail("timeoutSeconds вне допустимого диапазона");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("timeoutSeconds"));
        }
    }

    /**
     * Проект не того типа отбивает резолвер контекста — до запуска платформы, которая на
     * распаковку тратит секунды и захватывает ИБ.
     */
    @Test
    public void nonExternalObjectProject_isRefusedBeforeUnpacking() throws Exception {
        epfFile = Files.createTempDirectory("edt-mcp-test-epf-").resolve("АРМ_150626.epf");
        Files.writeString(epfFile, "epf-bytes");

        restorer = mock(IExternalObjectRestorer.class);
        factory  = mock(IImportOperationFactory.class);
        IWorkspaceRoot root = root(false);
        ExternalObjectImporter importer = new ExternalObjectImporter(restorer, factory,
            project -> { throw new ToolException("проект '" + project.getName()
                + "' не является проектом внешних отчётов и обработок"); });

        try {
            call(new ImportExternalObjectTool(() -> root, () -> importer));
            fail("проект не того типа — импорт обязан отбиваться");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(),
                expected.getMessage().contains("не является проектом внешних"));
        }
        verify(restorer, never()).restore(any(), any(), any(), any());
    }

    // --- схема ------------------------------------------------------------

    @Test
    public void schema_requiresProjectAndFile() throws Exception {
        Map<String, Object> schema = toolFor(ROOT_XML, false, true, Status.OK_STATUS).inputSchema();

        assertEquals(List.of("project", "file"), schema.get("required"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.keySet().toString(), props.keySet()
            .containsAll(List.of("project", "file", "overwrite", "timeoutSeconds")));
    }
}
