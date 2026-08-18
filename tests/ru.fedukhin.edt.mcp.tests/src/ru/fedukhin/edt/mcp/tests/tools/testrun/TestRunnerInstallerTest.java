package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller.InstallResult;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller.ManagedAppModuleEditor;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerInstaller.ModuleScaffolder;

public class TestRunnerInstallerTest {

    @Test public void install_configurationProject_createsModulesAndAppendsHandler() throws Exception {
        IConfigurationProject project = mock(IConfigurationProject.class);
        IProject iproject = mock(IProject.class);
        when(project.getProject()).thenReturn(iproject);
        when(iproject.getName()).thenReturn("Demo");

        ModuleScaffolder scaffolder = mock(ModuleScaffolder.class);
        when(scaffolder.exists(any(), anyString())).thenReturn(false);
        ManagedAppModuleEditor editor = mock(ManagedAppModuleEditor.class);
        when(editor.hasMarker(any())).thenReturn(false);

        TestRunnerInstaller installer = new TestRunnerInstaller(scaffolder, editor);
        InstallResult res = installer.install(project);

        assertEquals("configuration", res.mode());
        assertFalse(res.alreadyInstalled());
        assertEquals("EDT_MCP_TestRunner_Клиент_Demo", res.clientModule());
        assertEquals("EDT_MCP_TestRunner_Сервер_Demo", res.serverModule());
        assertTrue("configuration mode → warningInvasive", res.warningInvasive());
        verify(scaffolder).createClientModule(eq(iproject), eq("EDT_MCP_TestRunner_Клиент_Demo"));
        verify(scaffolder).createServerModule(eq(iproject), eq("EDT_MCP_TestRunner_Сервер_Demo"));
        verify(editor).appendConfigurationHandler(eq(iproject));
        verify(editor, never()).appendExtensionHandler(any());
    }

    @Test public void install_extensionProject_addsAfterAnnotationHandler() throws Exception {
        IExtensionProject project = mock(IExtensionProject.class);
        IProject iproject = mock(IProject.class);
        when(project.getProject()).thenReturn(iproject);
        when(iproject.getName()).thenReturn("DemoExt");

        ModuleScaffolder scaffolder = mock(ModuleScaffolder.class);
        when(scaffolder.exists(any(), anyString())).thenReturn(false);
        ManagedAppModuleEditor editor = mock(ManagedAppModuleEditor.class);
        when(editor.hasMarker(any())).thenReturn(false);

        TestRunnerInstaller installer = new TestRunnerInstaller(scaffolder, editor);
        InstallResult res = installer.install(project);

        assertEquals("extension", res.mode());
        assertFalse("extension mode → no invasive warning", res.warningInvasive());
        verify(editor).appendExtensionHandler(eq(iproject));
        verify(editor, never()).appendConfigurationHandler(any());
    }

    @Test public void install_alreadyInstalled_returnsAlreadyInstalledTrue_noScaffold() throws Exception {
        IConfigurationProject project = mock(IConfigurationProject.class);
        IProject iproject = mock(IProject.class);
        when(project.getProject()).thenReturn(iproject);
        when(iproject.getName()).thenReturn("Demo");

        ModuleScaffolder scaffolder = mock(ModuleScaffolder.class);
        when(scaffolder.exists(any(), anyString())).thenReturn(true);
        ManagedAppModuleEditor editor = mock(ManagedAppModuleEditor.class);
        when(editor.hasMarker(any())).thenReturn(true);

        TestRunnerInstaller installer = new TestRunnerInstaller(scaffolder, editor);
        InstallResult res = installer.install(project);

        assertTrue(res.alreadyInstalled());
        verify(scaffolder, never()).createClientModule(any(), anyString());
        verify(scaffolder, never()).createServerModule(any(), anyString());
        verify(editor, never()).appendConfigurationHandler(any());
        verify(editor, never()).appendExtensionHandler(any());
    }

    @Test public void install_unsupportedProjectKind_throws() {
        // IV8Project that is neither Configuration nor Extension
        IV8Project other = mock(IV8Project.class);
        IProject iproject = mock(IProject.class);
        when(other.getProject()).thenReturn(iproject);
        when(iproject.getName()).thenReturn("MyExternalObject");

        ModuleScaffolder scaffolder = mock(ModuleScaffolder.class);
        ManagedAppModuleEditor editor = mock(ManagedAppModuleEditor.class);
        TestRunnerInstaller installer = new TestRunnerInstaller(scaffolder, editor);
        try {
            installer.install(other);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue("message must mention Configuration", e.getMessage().contains("Configuration"));
            assertTrue("message must mention Extension", e.getMessage().contains("Extension"));
        }
    }

    @Test public void uninstall_existingInstall_removesEverything() throws Exception {
        IConfigurationProject project = mock(IConfigurationProject.class);
        IProject iproject = mock(IProject.class);
        when(project.getProject()).thenReturn(iproject);
        when(iproject.getName()).thenReturn("Demo");

        ModuleScaffolder scaffolder = mock(ModuleScaffolder.class);
        when(scaffolder.exists(any(), anyString())).thenReturn(true);
        ManagedAppModuleEditor editor = mock(ManagedAppModuleEditor.class);
        when(editor.hasMarker(any())).thenReturn(true);

        TestRunnerInstaller installer = new TestRunnerInstaller(scaffolder, editor);
        boolean removed = installer.uninstall(project);

        assertTrue(removed);
        // Снимаются оба образца имён: суффиксованные и легаси-модули до миграции.
        verify(scaffolder, times(4)).deleteModule(any(), anyString());
        verify(editor).removeMarkerBlock(any());
    }

    /**
     * Уникальность имён модулей нужна на уровне ИНФОРМАЦИОННОЙ БАЗЫ: два расширения
     * одной базы с одинаковыми именами приводят к тому, что 1С молча отключает
     * второе расширение целиком, и видно это только в журнале регистрации.
     */
    @Test public void moduleNames_areSuffixedByProject() {
        assertEquals("EDT_MCP_TestRunner_Клиент_Alpha", TestRunnerInstaller.clientModule("Alpha"));
        assertEquals("EDT_MCP_TestRunner_Сервер_Alpha", TestRunnerInstaller.serverModule("Alpha"));
    }

    /** Точка в имени проекта недопустима в идентификаторе общего модуля 1С. */
    @Test public void moduleNames_replaceIllegalCharacters() {
        String name = TestRunnerInstaller.serverModule("Demo.Расширение");
        assertFalse("точек быть не должно", name.contains("."));
        assertTrue(name.startsWith("EDT_MCP_TestRunner_Сервер_Demo_"));
    }

    @Test public void moduleNames_fitPlatformIdentifierLimit() {
        String name = TestRunnerInstaller.serverModule("Оченьдлинноеимяпроекта".repeat(6));
        assertTrue("идентификатор 1С не длиннее 80 символов, было " + name.length(),
            name.length() <= 80);
    }

    /** Модули без суффикса конфликтуют по имени — установка обязана их снести. */
    @Test public void install_removesLegacyModules() throws Exception {
        IConfigurationProject project = mock(IConfigurationProject.class);
        IProject iproject = mock(IProject.class);
        when(project.getProject()).thenReturn(iproject);
        when(iproject.getName()).thenReturn("Demo");

        ModuleScaffolder scaffolder = mock(ModuleScaffolder.class);
        when(scaffolder.exists(any(), anyString())).thenReturn(false);
        when(scaffolder.exists(any(), eq("CommonModule.EDT_MCP_TestRunner_Клиент"))).thenReturn(true);
        when(scaffolder.exists(any(), eq("CommonModule.EDT_MCP_TestRunner_Сервер"))).thenReturn(true);
        ManagedAppModuleEditor editor = mock(ManagedAppModuleEditor.class);
        when(editor.hasMarker(any())).thenReturn(false);

        new TestRunnerInstaller(scaffolder, editor).install(project);

        verify(scaffolder).deleteModule(eq(iproject), eq("CommonModule.EDT_MCP_TestRunner_Клиент"));
        verify(scaffolder).deleteModule(eq(iproject), eq("CommonModule.EDT_MCP_TestRunner_Сервер"));
        verify(scaffolder).createClientModule(eq(iproject), eq("EDT_MCP_TestRunner_Клиент_Demo"));
    }

    @Test public void uninstall_notInstalled_returnsFalse_noChange() throws Exception {
        IConfigurationProject project = mock(IConfigurationProject.class);
        IProject iproject = mock(IProject.class);
        when(project.getProject()).thenReturn(iproject);
        when(iproject.getName()).thenReturn("Demo");

        ModuleScaffolder scaffolder = mock(ModuleScaffolder.class);
        when(scaffolder.exists(any(), anyString())).thenReturn(false);
        ManagedAppModuleEditor editor = mock(ManagedAppModuleEditor.class);
        when(editor.hasMarker(any())).thenReturn(false);

        TestRunnerInstaller installer = new TestRunnerInstaller(scaffolder, editor);
        boolean removed = installer.uninstall(project);

        assertFalse(removed);
        verify(scaffolder, never()).deleteModule(any(), anyString());
        verify(editor, never()).removeMarkerBlock(any());
    }
}
