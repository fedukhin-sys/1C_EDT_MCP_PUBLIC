package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.BuildExternalObjectTool;
import ru.fedukhin.edt.mcp.tools.md.internal.ExternalObjectBuilder;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckMarker;
import ru.fedukhin.edt.mcp.tools.quality.internal.MarkerReader;

/**
 * {@code build_external_object} — единственный автоматический барьер между «битым BSL»
 * и собранным .epf: 1cv8 DESIGNER синтаксис не проверяет и молча кладёт сломанный
 * модуль в файл, поэтому precheck по маркерам обязателен ДО экспорта.
 *
 * <p>Реальную сборку тесты не гоняют: экспорт (внутренний EDT-сервис), резолв 1cv8.exe и
 * запуск процесса подменены сеймами. Проверяется оркестрация — порядок шагов, командная
 * строка, трактовка exit-кодов.
 */
public class BuildExternalObjectToolTest {

    private static final String EXE = "C:\\1cv8\\bin\\1cv8.exe";
    private static final String IB  = "/FC:\\ib\\service";

    private MarkerReader               markerReader;
    private ExternalObjectBuilder.ProjectExporter exporter;
    private ExternalObjectBuilder.ProcessFactory  processFactory;
    private Process                    process;
    private Path                       outFile;
    private Path                       exportDir;

    /**
     * Собирает инструмент на моках. {@code markers} — что вернёт MarkerReader;
     * {@code exitCode} — с чем завершится 1cv8; {@code producesEpf} — создаст ли он выходной файл.
     */
    private BuildExternalObjectTool toolFor(List<CheckMarker> markers, int exitCode, boolean producesEpf)
            throws Exception {
        outFile   = Files.createTempDirectory("edt-mcp-test-out-").resolve("Печать.epf");
        exportDir = Files.createTempDirectory("edt-mcp-test-xml-");
        // Экспортёр обязан положить корневой .xml объекта — по нему строится команда 1cv8.
        Files.writeString(exportDir.resolve("Печать.xml"), "<x/>");

        markerReader = mock(MarkerReader.class);
        when(markerReader.read(any(IProject.class), isNull(), isNull(), anySet(), isNull()))
            .thenReturn(markers);
        when(markerReader.read(any(IProject.class), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(markers);

        exporter = mock(ExternalObjectBuilder.ProjectExporter.class);

        process = mock(Process.class);
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            if (producesEpf) {
                Files.createDirectories(outFile.getParent());
                Files.writeString(outFile, "epf-bytes");
            }
            return true;
        });
        when(process.exitValue()).thenReturn(exitCode);

        processFactory = mock(ExternalObjectBuilder.ProcessFactory.class);
        when(processFactory.start(any(), any())).thenReturn(process);

        ExternalObjectBuilder builder = new ExternalObjectBuilder(
            exporter, version -> new File(EXE), processFactory, () -> exportDir);

        IFile mdoFile = mock(IFile.class);
        when(mdoFile.exists()).thenReturn(true);
        when(mdoFile.getContents()).thenAnswer(inv ->
            new ByteArrayInputStream("<x/>".getBytes(StandardCharsets.UTF_8)));
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("ВнешниеОбработки");
        when(project.getFile("src/ExternalDataProcessors/Печать/Печать.mdo")).thenReturn(mdoFile);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("ВнешниеОбработки")).thenReturn(project);

        return new BuildExternalObjectTool(() -> root, markerReader, builder, p -> "8.3.27");
    }

    private Map<String, Object> callOk() throws Exception {
        return call(toolFor(List.of(), 0, true));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(BuildExternalObjectTool tool) throws Exception {
        return (Map<String, Object>) tool.call(Map.of(
            "project",         "ВнешниеОбработки",
            "fqn",             "ExternalDataProcessor.Печать",
            "outPath",         outFile.toString(),
            "serviceInfobase", IB));
    }

    private List<String> capturedCommand() throws Exception {
        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        verify(processFactory).start(cmd.capture(), any());
        return cmd.getValue();
    }

    // --- happy path -------------------------------------------------------

    @Test
    public void buildsEpf_viaExportThenDesignerLoad() throws Exception {
        Map<String, Object> result = callOk();

        assertEquals(outFile.toString(), result.get("epfPath"));
        verify(exporter).export(eq("ВнешниеОбработки"), any(Path.class));

        List<String> cmd = capturedCommand();
        assertEquals(EXE, cmd.get(0));
        assertEquals("DESIGNER", cmd.get(1));
        assertTrue("строка подключения к служебной ИБ обязана быть в команде", cmd.contains(IB));

        int load = cmd.indexOf("/LoadExternalDataProcessorOrReportFromFiles");
        assertTrue("нужен /LoadExternalDataProcessorOrReportFromFiles", load > 0);
        assertEquals("первый аргумент — корневой .xml объекта",
            exportDir.resolve("Печать.xml").toString(), cmd.get(load + 1));
        assertEquals("второй аргумент — выходной .epf", outFile.toString(), cmd.get(load + 2));
    }

    /** 1cv8 при ошибке ничего не пишет в stderr — без /Out диагностики не будет вовсе. */
    @Test
    public void commandLine_capturesDesignerLogViaOut() throws Exception {
        callOk();

        List<String> cmd = capturedCommand();
        int out = cmd.indexOf("/Out");
        assertTrue("нужен /Out для диагностики", out > 0);
        assertTrue(cmd.contains("/DisableStartupDialogs"));
        assertTrue(cmd.contains("/DisableStartupMessages"));
    }

    // --- precheck ---------------------------------------------------------

    @Test
    public void precheck_blocksOnBslCompileError_andSkipsExportAndProcess() throws Exception {
        BuildExternalObjectTool tool = toolFor(
            List.of(new CheckMarker("m1", "ВнешниеОбработки", "", 12, "error", "BslEditor",
                "edt", "Ожидается выражение", null)), 0, true);

        try {
            call(tool);
            fail("сборка с BSL-компиляционной ошибкой обязана отбиваться");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Ожидается выражение"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("check_list_markers"));
        }

        verify(exporter, never()).export(anyString(), any(Path.class));
        verify(processFactory, never()).start(any(), any());
    }

    @Test
    public void precheck_nonBlockingMarkers_landInWarnings() throws Exception {
        BuildExternalObjectTool tool = toolFor(
            List.of(new CheckMarker("m1", "ВнешниеОбработки", "", 3, "error", "MdValidationChecker",
                "edt", "orphan uuid", null)), 0, true);

        Map<String, Object> result = call(tool);

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertTrue(warnings.toString(),
            warnings.stream().anyMatch(w -> w.contains("orphan uuid")
                                         && w.contains("MdValidationChecker")));
    }

    /** Мгновенный «успех» 1cv8 — класс BUG-18: сборка не могла отработать за 0 мс. */
    @Test
    public void suspiciouslyFastSuccess_addsWarning() throws Exception {
        Map<String, Object> result = callOk();

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.contains("BUG-18")));
    }

    // --- exit codes -------------------------------------------------------

    @Test
    public void nonZeroExit_isReported() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), 1, false);

        try {
            call(tool);
            fail("ненулевой exit 1cv8 обязан быть ошибкой");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("1cv8"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("exit"));
        }
    }

    /** BUG-18-класс: 1cv8 умеет вернуть 0, не сделав ничего. Верим файлу, а не exit-коду. */
    @Test
    public void zeroExitWithoutEpf_isReported() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), 0, false);

        try {
            call(tool);
            fail("exit=0 без выходного файла обязан быть ошибкой");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("exit=0"));
        }
    }

    @Test
    public void timeout_killsProcess() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), 0, false);
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);

        try {
            call(tool);
            fail("таймаут обязан быть ошибкой");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("timeout"));
        }
        verify(process).destroyForcibly();
    }

    // --- argument validation ----------------------------------------------

    @Test
    public void unsupportedFqnKind_isRejected() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), 0, true);

        try {
            tool.call(Map.of("project", "ВнешниеОбработки", "fqn", "Catalog.Товары",
                "outPath", outFile.toString(), "serviceInfobase", IB));
            fail("build_external_object применим только к внешним объектам");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ExternalDataProcessor"));
        }
        verify(processFactory, never()).start(any(), any());
    }

    @Test
    public void malformedServiceInfobase_isRejected() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), 0, true);

        try {
            tool.call(Map.of("project", "ВнешниеОбработки", "fqn", "ExternalDataProcessor.Печать",
                "outPath", outFile.toString(), "serviceInfobase", "C:\\ib\\service"));
            fail("строка подключения обязана быть в форме /F… или /S…");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("/F"));
        }
    }

    @Test
    public void missingServiceInfobase_isRejectedWithActionableMessage() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), 0, true);

        try {
            tool.call(Map.of("project", "ВнешниеОбработки", "fqn", "ExternalDataProcessor.Печать",
                "outPath", outFile.toString()));
            fail("без служебной ИБ собрать нечем");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("serviceInfobase"));
        }
        verify(processFactory, never()).start(any(), any());
    }

    @Test
    public void unknownObject_isRejectedBeforePrecheck() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), 0, true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("ВнешниеОбработки");
        IFile absent = mock(IFile.class);
        when(absent.exists()).thenReturn(false);
        when(project.getFile(anyString())).thenReturn(absent);
        when(root.getProject("ВнешниеОбработки")).thenReturn(project);

        BuildExternalObjectTool t2 = new BuildExternalObjectTool(
            () -> root, markerReader,
            new ExternalObjectBuilder(exporter, v -> new File(EXE), processFactory, () -> exportDir),
            p -> "8.3.27");

        try {
            call(t2);
            fail("несуществующий объект обязан отбиваться");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(".mdo"));
        }
    }

    // --- contract ---------------------------------------------------------

    @Test
    public void schema_declaresContract() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), 0, true);
        Map<String, Object> schema = tool.inputSchema();

        assertEquals("build_external_object", tool.name());
        assertEquals(List.of("project", "fqn", "outPath"), schema.get("required"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("serviceInfobase"));
    }
}
