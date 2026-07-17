package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.ListMdObjectsTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;

/**
 * Проекты внешних объектов ({@code create_project type=external-object}) не имеют Configuration
 * root, поэтому {@code list_md_objects} падал на них с «cannot resolve Configuration root» —
 * перечислить собственные обработки проекта было нечем.
 *
 * <p>Для таких проектов работает файловый скан {@code src/ExternalDataProcessors|ExternalReports}.
 */
public class ListMdObjectsExternalProjectTest {

    /** Папка одного внешнего объекта: {@code src/<folder>/<name>/<name>.mdo}. */
    private static IFolder objectFolder(String name) throws Exception {
        IFile mdo = mock(IFile.class);
        when(mdo.exists()).thenReturn(true);
        IFolder folder = mock(IFolder.class);
        when(folder.getName()).thenReturn(name);
        when(folder.getFile(name + ".mdo")).thenReturn(mdo);
        return folder;
    }

    private static IFolder srcFolder(boolean exists, IFolder... children) throws Exception {
        IFolder folder = mock(IFolder.class);
        when(folder.exists()).thenReturn(exists);
        when(folder.members()).thenReturn(children);
        return folder;
    }

    private static IProject externalObjectProject(IFolder dataProcessors, IFolder reports)
        throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("ВнешниеОбработки");
        when(project.getFolder("src/ExternalDataProcessors")).thenReturn(dataProcessors);
        when(project.getFolder("src/ExternalReports")).thenReturn(reports);
        return project;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object result) {
        return (List<Map<String, Object>>) ((Map<String, Object>) result).get("objects");
    }

    @Test
    public void externalObjectProject_isListedByFileScan_withoutTouchingBm() throws Exception {
        IProject project = externalObjectProject(
            srcFolder(true, objectFolder("ЗагрузкаЦен"), objectFolder("ВыгрузкаОстатков")),
            srcFolder(true, objectFolder("ОтчётПоПродажам")));
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("ВнешниеОбработки")).thenReturn(project);

        IBmModelManager bm = mock(IBmModelManager.class);
        ListMdObjectsTool tool = new ListMdObjectsTool(() -> root, bm,
            mock(IConfigurationProvider.class), new MdObjectRegistry());

        List<Map<String, Object>> objects = listOf(tool.call(Map.of("project", "ВнешниеОбработки")));

        assertEquals(3, objects.size());
        assertTrue(objects.contains(Map.of(
            "kind", "ExternalDataProcessor",
            "name", "ЗагрузкаЦен",
            "fqn",  "ExternalDataProcessor.ЗагрузкаЦен")));
        assertTrue(objects.contains(Map.of(
            "kind", "ExternalReport",
            "name", "ОтчётПоПродажам",
            "fqn",  "ExternalReport.ОтчётПоПродажам")));
        // У такого проекта Configuration root нет — BM-транзакция бессмысленна.
        verify(bm, never()).executeReadOnlyTask(eq(project), any());
    }

    @Test
    public void kindFilter_appliesToExternalObjects() throws Exception {
        IProject project = externalObjectProject(
            srcFolder(true, objectFolder("ЗагрузкаЦен")),
            srcFolder(true, objectFolder("ОтчётПоПродажам")));
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("ВнешниеОбработки")).thenReturn(project);

        ListMdObjectsTool tool = new ListMdObjectsTool(() -> root, mock(IBmModelManager.class),
            mock(IConfigurationProvider.class), new MdObjectRegistry());

        List<Map<String, Object>> objects = listOf(tool.call(
            Map.of("project", "ВнешниеОбработки", "kind", "ExternalReport")));

        assertEquals(1, objects.size());
        assertEquals("ExternalReport.ОтчётПоПродажам", objects.get(0).get("fqn"));
    }

    /** Обычная конфигурация не имеет этих папок и обязана по-прежнему идти BM-маршрутом. */
    @Test
    public void configurationProject_stillUsesBmRoute() throws Exception {
        IProject project = externalObjectProject(srcFolder(false), srcFolder(false));
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("ВнешниеОбработки")).thenReturn(project);

        IBmModelManager bm = mock(IBmModelManager.class);
        ListMdObjectsTool tool = new ListMdObjectsTool(() -> root, bm,
            mock(IConfigurationProvider.class), new MdObjectRegistry());

        tool.call(Map.of("project", "ВнешниеОбработки"));

        verify(bm).executeReadOnlyTask(eq(project), any());
    }
}
