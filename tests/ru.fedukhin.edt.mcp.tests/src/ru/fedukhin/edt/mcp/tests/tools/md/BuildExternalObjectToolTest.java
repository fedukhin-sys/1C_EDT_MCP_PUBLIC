package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.BuildExternalObjectTool;
import ru.fedukhin.edt.mcp.tools.md.internal.ExternalObjectBuilder;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckMarker;
import ru.fedukhin.edt.mcp.tools.quality.internal.MarkerReader;

/**
 * {@code build_external_object} — сборка внешнего объекта в .epf штатным сервисом EDT
 * {@link IExternalObjectDumper} (тот же путь, что «Экспорт» в IDE: XML-выгрузка + запуск
 * платформы, ИБ и учётка берутся из ассоциированного с проектом приложения).
 *
 * <p>Инструмент отвечает за то, чего сервис не делает: precheck по маркерам (ни выгрузка,
 * ни платформа синтаксис BSL не проверяют — сломанный модуль молча уехал бы в .epf),
 * удаление прошлого файла, таймаут и проверку результата на диске.
 *
 * <p>Сам dump тесты не гоняют: сервис EDT и резолв объекта в BM подменены сеймами.
 */
public class BuildExternalObjectToolTest {

    private MarkerReader                        markerReader;
    private IExternalObjectDumper               dumper;
    private ExternalObjectBuilder.ObjectResolver resolver;
    private Path                                outFile;
    private EObject                             externalObject;

    /**
     * Собирает инструмент на моках. {@code markers} — что вернёт MarkerReader;
     * {@code producesEpf} — создаст ли dump выходной файл.
     */
    private BuildExternalObjectTool toolFor(List<CheckMarker> markers, boolean producesEpf)
            throws Exception {
        outFile = Files.createTempDirectory("edt-mcp-test-out-").resolve("Печать.epf");

        markerReader = mock(MarkerReader.class);
        // precheck скоупится на каталог целевой обработки — path непустой (не isNull).
        when(markerReader.read(any(IProject.class), anyString(), isNull(), isNull(), isNull()))
            .thenReturn(markers);

        externalObject = mock(EObject.class);
        resolver = mock(ExternalObjectBuilder.ObjectResolver.class);
        when(resolver.resolve(any(IProject.class), anyString())).thenReturn(externalObject);

        dumper = mock(IExternalObjectDumper.class);
        if (producesEpf) {
            doAnswer(inv -> {
                Path target = inv.getArgument(2);
                Files.createDirectories(target.getParent());
                Files.writeString(target, "epf-bytes");
                return null;
            }).when(dumper).dump(any(), any(), any(), any());
        }

        IWorkspaceRoot root = root();
        return new BuildExternalObjectTool(() -> root, markerReader,
            new ExternalObjectBuilder(dumper, resolver));
    }

    private IWorkspaceRoot root() {
        IFile mdoFile = mock(IFile.class);
        when(mdoFile.exists()).thenReturn(true);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("ВнешниеОбработки");
        when(project.getFile("src/ExternalDataProcessors/Печать/Печать.mdo")).thenReturn(mdoFile);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("ВнешниеОбработки")).thenReturn(project);
        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(BuildExternalObjectTool tool) throws Exception {
        return (Map<String, Object>) tool.call(Map.of(
            "project", "ВнешниеОбработки",
            "fqn",     "ExternalDataProcessor.Печать",
            "outPath", outFile.toString()));
    }

    // --- happy path -------------------------------------------------------

    @Test
    public void buildsEpf_viaEdtDumpService() throws Exception {
        Map<String, Object> result = call(toolFor(List.of(), true));

        assertEquals(outFile.toString(), result.get("epfPath"));
        assertEquals("ExternalDataProcessor.Печать", result.get("fqn"));
        verify(resolver).resolve(any(IProject.class), eq("ExternalDataProcessor.Печать"));
        verify(dumper).dump(any(IProject.class), eq(externalObject), eq(outFile),
            any(IProgressMonitor.class));
    }

    /**
     * Файл мог остаться от прошлой сборки: без удаления «dump отработал вхолостую»
     * выглядел бы как успех.
     */
    @Test
    public void staleOutputFile_isRemovedBeforeDump() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), false);
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, "старый epf");

        doAnswer(inv -> {
            assertFalse("прошлый .epf обязан быть удалён до dump", Files.exists(outFile));
            return null;
        }).when(dumper).dump(any(), any(), any(), any());

        try {
            call(tool);
            fail("dump не создал файл — обязана быть ошибка");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("не создан"));
        }
    }

    // --- precheck ---------------------------------------------------------

    @Test
    public void precheck_blocksOnBslCompileError_andSkipsDump() throws Exception {
        BuildExternalObjectTool tool = toolFor(
            List.of(new CheckMarker("m1", "ВнешниеОбработки", "", 12, "error", "BslEditor",
                "edt", "Ожидается выражение", null)), true);

        try {
            call(tool);
            fail("сборка с BSL-компиляционной ошибкой обязана отбиваться");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Ожидается выражение"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("check_list_markers"));
        }

        verify(dumper, never()).dump(any(), any(), any(), any());
    }

    /**
     * External-object проект держит десятки обработок; .epf собирается для одной, поэтому
     * precheck обязан смотреть маркеры только её каталога — иначе блокер в соседней обработке
     * (её битый BSL в этот .epf не попадёт) не даст собрать целевую.
     */
    @Test
    public void precheck_isScopedToTargetObjectDir() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), true);
        call(tool);
        verify(markerReader).read(any(IProject.class), eq("src/ExternalDataProcessors/Печать"),
            isNull(), isNull(), isNull());
    }

    @Test
    public void precheck_nonBlockingMarkers_landInWarnings() throws Exception {
        BuildExternalObjectTool tool = toolFor(
            List.of(new CheckMarker("m1", "ВнешниеОбработки", "", 3, "error", "MdValidationChecker",
                "edt", "orphan uuid", null)), true);

        Map<String, Object> result = call(tool);

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertTrue(warnings.toString(),
            warnings.stream().anyMatch(w -> w.contains("orphan uuid")
                                         && w.contains("MdValidationChecker")));
    }

    // --- результат dump ---------------------------------------------------

    /**
     * Сервис бросает CoreException, когда у проекта нет ИБ для сборки. Текст статуса —
     * единственная диагностика, и без подсказки про associate_infobase он необъясним.
     */
    @Test
    public void dumpFailure_reportsStatusAndSuggestsInfobaseAssociation() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), false);
        doThrow(new CoreException(Status.error(
                "Не найдено разрабатываемых приложений информационной базы для проекта \"Демо\"")))
            .when(dumper).dump(any(), any(), any(), any());

        try {
            call(tool);
            fail("провал dump обязан быть ошибкой");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(),
                expected.getMessage().contains("Не найдено разрабатываемых приложений"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("associate_infobase"));
        }
    }

    /**
     * Первое обращение к ИБ в свежем сеансе EDT переводит её в connected и падает
     * IllegalArgumentException «already connected»; повтор проходит. Инструмент обязан
     * ретраить этот транзиентный отказ, а не отдавать его наружу (live-smoke 2026-07-17).
     */
    @Test
    public void alreadyConnected_isRetriedOnce_thenSucceeds() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), false);
        doThrow(new IllegalArgumentException("Infobase Dandy 030526 is already connected"))
            .doAnswer(inv -> {
                Path target = inv.getArgument(2);
                Files.createDirectories(target.getParent());
                Files.writeString(target, "epf-bytes");
                return null;
            }).when(dumper).dump(any(), any(), any(), any());

        Map<String, Object> result = call(tool);
        assertEquals(outFile.toString(), result.get("epfPath"));
        verify(dumper, org.mockito.Mockito.times(2)).dump(any(), any(), any(), any());
    }

    /** Если и повтор упал «already connected» — честная ошибка с оригинальным текстом EDT. */
    @Test
    public void alreadyConnected_persistent_reportsHonestError() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), false);
        doThrow(new IllegalArgumentException("Infobase Dandy 030526 is already connected"))
            .when(dumper).dump(any(), any(), any(), any());

        try {
            call(tool);
            fail("устойчивый already connected обязан быть ошибкой");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("already connected"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("занята"));
        }
    }

    /** Сервис вернулся без ошибки, файла нет — верим диску, а не отсутствию исключения. */
    @Test
    public void silentSuccessWithoutFile_isReported() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), false);

        try {
            call(tool);
            fail("dump без выходного файла обязан быть ошибкой");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("не создан"));
        }
    }

    @Test
    public void emptyOutputFile_isReported() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), false);
        doAnswer(inv -> {
            Path target = inv.getArgument(2);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "");
            return null;
        }).when(dumper).dump(any(), any(), any(), any());

        try {
            call(tool);
            fail("пустой .epf обязан быть ошибкой");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("пуст"));
        }
    }

    /**
     * Платформа умеет зависнуть на занятой ИБ — таймаут обязан отменить операцию, а не ждать вечно.
     * Проверяется на builder'е: минимальный таймаут инструмента (30 с) тест бы просто прождал.
     */
    @Test
    public void timeout_cancelsMonitorAndReports() throws Exception {
        toolFor(List.of(), false);
        IProgressMonitor[] seen = new IProgressMonitor[1];
        doAnswer(inv -> {
            seen[0] = inv.getArgument(3);
            for (int i = 0; i < 200 && !seen[0].isCanceled(); i++) Thread.sleep(50);
            return null;
        }).when(dumper).dump(any(), any(), any(), any());

        ExternalObjectBuilder builder = new ExternalObjectBuilder(dumper, resolver);
        try {
            builder.build(new ExternalObjectBuilder.BuildRequest(
                mock(IProject.class), "ExternalDataProcessor.Печать", outFile, 1));
            fail("таймаут обязан быть ошибкой");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("timeout"));
        }
        assertTrue("монитор обязан быть отменён", seen[0].isCanceled());
    }

    // --- argument validation ----------------------------------------------

    @Test
    public void unsupportedFqnKind_isRejected() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), true);

        try {
            tool.call(Map.of("project", "ВнешниеОбработки", "fqn", "Catalog.Товары",
                "outPath", outFile.toString()));
            fail("build_external_object применим только к внешним объектам");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ExternalDataProcessor"));
        }
        verify(dumper, never()).dump(any(), any(), any(), any());
    }

    @Test
    public void unknownObject_isRejectedBeforePrecheck() throws Exception {
        toolFor(List.of(), true);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("ВнешниеОбработки");
        IFile absent = mock(IFile.class);
        when(absent.exists()).thenReturn(false);
        when(project.getFile(anyString())).thenReturn(absent);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("ВнешниеОбработки")).thenReturn(project);

        BuildExternalObjectTool tool = new BuildExternalObjectTool(
            () -> root, markerReader, new ExternalObjectBuilder(dumper, resolver));

        try {
            call(tool);
            fail("несуществующий объект обязан отбиваться");
        } catch (ToolException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(".mdo"));
        }
    }

    // --- contract ---------------------------------------------------------

    @Test
    public void schema_declaresContract() throws Exception {
        BuildExternalObjectTool tool = toolFor(List.of(), true);
        Map<String, Object> schema = tool.inputSchema();

        assertEquals("build_external_object", tool.name());
        assertEquals(List.of("project", "fqn", "outPath"), schema.get("required"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(List.of("project", "fqn", "outPath", "timeoutSeconds"),
            List.copyOf(props.keySet()));
        assertFalse("служебная ИБ больше не параметр: её даёт ассоциация проекта",
            props.containsKey("serviceInfobase"));
    }
}
