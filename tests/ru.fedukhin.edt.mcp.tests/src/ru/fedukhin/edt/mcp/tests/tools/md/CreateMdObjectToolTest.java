package ru.fedukhin.edt.mcp.tests.tools.md;

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
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.CreateMdObjectTool;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.ModuleFileBootstrap;

public class CreateMdObjectToolTest {

    private final MdObjectRegistry registry = new MdObjectRegistry();

    /** Builds IProject mock that satisfies ModuleFileBootstrap filesystem calls. */
    private IProject makeProjectWithModuleFs(String modName) {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");

        IFolder srcFolder       = mock(IFolder.class);
        IFolder commonModules   = mock(IFolder.class);
        IFolder moduleFolder    = mock(IFolder.class);
        IFile   moduleFile      = mock(IFile.class);

        when(project.getFolder("src")).thenReturn(srcFolder);
        when(srcFolder.getFolder("CommonModules")).thenReturn(commonModules);
        when(commonModules.getFolder(modName)).thenReturn(moduleFolder);
        when(moduleFolder.getFile("Module.bsl")).thenReturn(moduleFile);
        when(moduleFolder.exists()).thenReturn(false);
        when(moduleFile.exists()).thenReturn(false);
        return project;
    }

    @Test
    public void createCatalog_returnsModulePath() throws Exception {
        // Bug #4 fix: Catalog теперь возвращает modulePath (ObjectModule.bsl),
        // файл lazy (не создаётся физически — только путь для add_extension_method_override).
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        // Setup BM so factory can create the catalog
        IBmTransaction txn = mock(IBmTransaction.class);
        Configuration cfg = mock(Configuration.class,
                withSettings().extraInterfaces(IBmObject.class));
        when(cfg.getCatalogs()).thenReturn(new BasicEList<>());
        when(txn.getTopObjectByFqn(eq("Configuration"))).thenReturn((IBmObject) cfg);
        when(txn.getTopObjectByFqn(eq("Catalog.Goods"))).thenReturn(null);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        MdObjectFactory factory = new MdObjectFactory(executor, mock(IConfigurationProvider.class), registry);
        CreateMdObjectTool tool = new CreateMdObjectTool(() -> root, factory, registry, new ModuleFileBootstrap());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "kind", "Catalog", "name", "Goods"));

        assertEquals("Catalog.Goods", result.get("fqn"));
        assertEquals("Catalog", result.get("kind"));
        assertEquals("Goods", result.get("name"));
        assertEquals("src/Catalogs/Goods/ObjectModule.bsl", result.get("modulePath"));
    }

    @Test
    public void createDocument_returnsModulePath() throws Exception {
        // Bug #4 fix: Document modulePath возвращается лениво (без физического файла).
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        IBmTransaction txn = mock(IBmTransaction.class);
        Configuration cfg = mock(Configuration.class,
                withSettings().extraInterfaces(IBmObject.class));
        when(cfg.getDocuments()).thenReturn(new BasicEList<>());
        when(txn.getTopObjectByFqn(eq("Configuration"))).thenReturn((IBmObject) cfg);
        when(txn.getTopObjectByFqn(eq("Document.Invoice"))).thenReturn(null);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        MdObjectFactory factory = new MdObjectFactory(executor, mock(IConfigurationProvider.class), registry);
        CreateMdObjectTool tool = new CreateMdObjectTool(() -> root, factory, registry, new ModuleFileBootstrap());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "kind", "Document", "name", "Invoice"));

        assertEquals("src/Documents/Invoice/ObjectModule.bsl", result.get("modulePath"));
    }

    @Test
    public void createCommonModule_returnsModulePath() throws Exception {
        IProject project = makeProjectWithModuleFs("Helper");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        IBmTransaction txn = mock(IBmTransaction.class);
        Configuration cfg = mock(Configuration.class,
                withSettings().extraInterfaces(IBmObject.class));
        when(cfg.getCommonModules()).thenReturn(new BasicEList<>());
        when(txn.getTopObjectByFqn(eq("Configuration"))).thenReturn((IBmObject) cfg);
        when(txn.getTopObjectByFqn(eq("CommonModule.Helper"))).thenReturn(null);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        MdObjectFactory factory = new MdObjectFactory(executor, mock(IConfigurationProvider.class), registry);
        CreateMdObjectTool tool = new CreateMdObjectTool(() -> root, factory, registry, new ModuleFileBootstrap());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "kind", "CommonModule", "name", "Helper"));

        assertEquals("CommonModule.Helper", result.get("fqn"));
        assertEquals("src/CommonModules/Helper/Module.bsl", result.get("modulePath"));
    }

    @Test(expected = ToolException.class)
    public void unknownKind_throwsToolException() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(mock(IBmTransaction.class));
        }).when(executor).execute(eq(project), any(), any());

        MdObjectFactory factory = new MdObjectFactory(executor, mock(IConfigurationProvider.class), registry);
        CreateMdObjectTool tool = new CreateMdObjectTool(() -> root, factory, registry, new ModuleFileBootstrap());
        tool.call(Map.of("project", "Demo", "kind", "Frobnicator", "name", "Foo"));
    }
}
