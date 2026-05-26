package ru.fedukhin.edt.mcp.tests.tools.tests;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.tests.ListTestModulesTool;
import ru.fedukhin.edt.mcp.tools.tests.internal.TestModuleHeuristic;

public class ListTestModulesToolTest {

    private static IProject projectMock(String name, IWorkspaceRoot root) {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(true);
        when(p.getName()).thenReturn(name);
        when(root.getProject(name)).thenReturn(p);
        return p;
    }

    private static void captureAndRunTask(IBmModelManager bm, IProject project, IBmTransaction txn) {
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(1);
            task.execute(txn);
            return null;
        }).when(bm).executeReadOnlyTask(eq(project), any());
    }

    // Test 1: non-test module is not included in results
    @Test
    public void nonTestModule_notIncluded() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IConfigurationProvider cfgP = mock(IConfigurationProvider.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        CommonModule cm = mock(CommonModule.class, withSettings().extraInterfaces(IBmObject.class));
        when(cm.getName()).thenReturn("ОбщийМодуль");
        EList<CommonModule> modules = new BasicEList<>(List.of(cm));

        Configuration cfg = mock(Configuration.class, withSettings().extraInterfaces(IBmObject.class));
        when(cfg.getCommonModules()).thenReturn(modules);
        when(txn.getTopObjectByFqn("Configuration")).thenReturn((IBmObject) cfg);

        captureAndRunTask(bm, project, txn);

        // IFile for module not found → empty text → heuristic returns null → not a test module
        IFolder src = mock(IFolder.class);
        IFolder cml = mock(IFolder.class);
        IFolder mf = mock(IFolder.class);
        IFile bslFile = mock(IFile.class);
        when(project.getFolder("src")).thenReturn(src);
        when(src.getFolder("CommonModules")).thenReturn(cml);
        when(cml.getFolder("ОбщийМодуль")).thenReturn(mf);
        when(mf.getFile("Module.bsl")).thenReturn(bslFile);
        when(bslFile.exists()).thenReturn(false);
        // Need IProject.getFile to work
        when(project.getFile("src/CommonModules/ОбщийМодуль/Module.bsl")).thenReturn(bslFile);
        when(bslFile.exists()).thenReturn(false);

        ListTestModulesTool tool = new ListTestModulesTool(() -> root, bm, cfgP, new TestModuleHeuristic());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of("project", "Demo"));
        @SuppressWarnings("unchecked")
        List<?> testModules = (List<?>) result.get("testModules");
        assertEquals(0, testModules.size());
    }

    // Test 2: test module with RU name is included
    @Test
    public void testModuleByName_isIncluded() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IConfigurationProvider cfgP = mock(IConfigurationProvider.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        CommonModule cm = mock(CommonModule.class, withSettings().extraInterfaces(IBmObject.class));
        when(cm.getName()).thenReturn("КаталогТесты");
        EList<CommonModule> modules = new BasicEList<>(List.of(cm));

        Configuration cfg = mock(Configuration.class, withSettings().extraInterfaces(IBmObject.class));
        when(cfg.getCommonModules()).thenReturn(modules);
        when(txn.getTopObjectByFqn("Configuration")).thenReturn((IBmObject) cfg);

        captureAndRunTask(bm, project, txn);

        IFile bslFile = mock(IFile.class);
        when(project.getFile("src/CommonModules/КаталогТесты/Module.bsl")).thenReturn(bslFile);
        when(bslFile.exists()).thenReturn(true);
        when(bslFile.getCharset()).thenReturn("UTF-8");
        byte[] content = "#Region Public\r\n".getBytes("UTF-8");
        when(bslFile.getContents()).thenReturn(new ByteArrayInputStream(content));

        ListTestModulesTool tool = new ListTestModulesTool(() -> root, bm, cfgP, new TestModuleHeuristic());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of("project", "Demo"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testModules = (List<Map<String, Object>>) result.get("testModules");
        assertEquals(1, testModules.size());
        assertEquals("CommonModule.КаталогТесты", testModules.get(0).get("fqn"));
        assertEquals("ru", testModules.get(0).get("language"));
    }

    // Test 3: unknown project throws ToolException
    @Test(expected = ToolException.class)
    public void unknownProject_throwsToolException() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(false);
        when(root.getProject("Unknown")).thenReturn(p);
        IBmModelManager bm = mock(IBmModelManager.class);
        IConfigurationProvider cfgP = mock(IConfigurationProvider.class);

        ListTestModulesTool tool = new ListTestModulesTool(() -> root, bm, cfgP, new TestModuleHeuristic());
        tool.call(Map.of("project", "Unknown"));
    }
}
