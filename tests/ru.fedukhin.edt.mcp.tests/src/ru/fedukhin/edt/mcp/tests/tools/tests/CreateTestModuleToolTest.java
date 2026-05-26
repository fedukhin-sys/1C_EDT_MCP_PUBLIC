package ru.fedukhin.edt.mcp.tests.tools.tests;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.emf.common.util.BasicEList;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.ModuleFileBootstrap;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;
import ru.fedukhin.edt.mcp.tools.tests.CreateTestModuleTool;

public class CreateTestModuleToolTest {

    private final MdObjectRegistry registry = new MdObjectRegistry();

    private IProject makeProject(String name, IWorkspaceRoot root, IBmTransaction txn,
                                 BmPersistentExecutor executor) throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn(name);
        when(root.getProject(name)).thenReturn(project);

        // Filesystem mocks for ModuleFileBootstrap
        IFolder src = mock(IFolder.class);
        IFolder cml = mock(IFolder.class);
        IFolder mf  = mock(IFolder.class);
        IFile   bsl = mock(IFile.class);
        when(project.getFolder("src")).thenReturn(src);
        when(src.getFolder("CommonModules")).thenReturn(cml);
        when(cml.getFolder(any(String.class))).thenReturn(mf);
        when(mf.getFile("Module.bsl")).thenReturn(bsl);
        when(mf.exists()).thenReturn(false);
        when(bsl.exists()).thenReturn(false);
        when(project.getFile(any(String.class))).thenReturn(bsl);

        // BM: createModule creates CommonModule, second task sets Server
        Configuration cfg = mock(Configuration.class, withSettings().extraInterfaces(IBmObject.class));
        when(cfg.getCommonModules()).thenReturn(new BasicEList<>());
        when(txn.getTopObjectByFqn("Configuration")).thenReturn((IBmObject) cfg);
        // First call to getTopObjectByFqn("CommonModule.*") is the uniqueness check → null.
        // Subsequent calls (from the setServer task) return a mock CommonModule.
        CommonModule cm = mock(CommonModule.class, withSettings().extraInterfaces(IBmObject.class));
        when(txn.getTopObjectByFqn(any())).thenAnswer(inv -> {
            String argFqn = inv.getArgument(0);
            if ("Configuration".equals(argFqn)) return (IBmObject) cfg;
            return null; // existence check returns null → not duplicate
        });
        // Override for second BM task (setServer): use a custom doAnswer on executor
        // that distinguishes create from setServer by call count
        final int[] callCount = {0};
        doAnswer(inv -> {
            callCount[0]++;
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            if (callCount[0] >= 2) {
                // Second call is setServer — now return the CommonModule mock
                when(txn.getTopObjectByFqn(any())).thenReturn((IBmObject) cm);
            }
            task.execute(txn);
            return null;
        }).when(executor).execute(eq(project), any(), any());

        return project;
    }

    // Test 1: happy-path RU → CommonModule created, fqn returned, modulePath set
    @Test
    public void happyPathRu_createModule() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        IBmTransaction txn = mock(IBmTransaction.class);
        IProject project = makeProject("Demo", root, txn, executor);

        MdObjectFactory factory = new MdObjectFactory(executor, mock(IConfigurationProvider.class), registry);
        PropertyAccessor propAcc = new PropertyAccessor(mock(IV8ProjectManager.class));
        CreateTestModuleTool tool = new CreateTestModuleTool(() -> root, executor, factory,
                new MdObjectLocator(), propAcc, new ModuleFileBootstrap());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "name", "КаталогТесты"));

        assertEquals("CommonModule.КаталогТесты", result.get("fqn"));
        assertEquals("ru", result.get("language"));
        assertNotNull(result.get("modulePath"));
    }

    // Test 2: happy-path EN → language "en" in result
    @Test
    public void happyPathEn_createModule() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        IBmTransaction txn = mock(IBmTransaction.class);
        IProject project = makeProject("Demo", root, txn, executor);

        MdObjectFactory factory = new MdObjectFactory(executor, mock(IConfigurationProvider.class), registry);
        PropertyAccessor propAcc = new PropertyAccessor(mock(IV8ProjectManager.class));
        CreateTestModuleTool tool = new CreateTestModuleTool(() -> root, executor, factory,
                new MdObjectLocator(), propAcc, new ModuleFileBootstrap());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "name", "CatalogTests", "language", "en"));

        assertEquals("CommonModule.CatalogTests", result.get("fqn"));
        assertEquals("en", result.get("language"));
    }

    // Test 3: missing project → ToolException
    @Test(expected = ToolException.class)
    public void missingProject_throwsToolException() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(false);
        when(root.getProject("Missing")).thenReturn(p);
        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        MdObjectFactory factory = new MdObjectFactory(executor, mock(IConfigurationProvider.class), registry);
        PropertyAccessor propAcc = new PropertyAccessor(mock(IV8ProjectManager.class));

        CreateTestModuleTool tool = new CreateTestModuleTool(() -> root, executor, factory,
                new MdObjectLocator(), propAcc, new ModuleFileBootstrap());
        tool.call(Map.of("project", "Missing", "name", "MyTest"));
    }
}
