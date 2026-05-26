package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;

public class MdObjectFactoryTest {

    private final MdObjectRegistry registry = new MdObjectRegistry();

    @Test
    public void createCatalog_addsToContainmentAndAttaches() throws Exception {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");
        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        IConfigurationProvider cfgProvider = mock(IConfigurationProvider.class);

        IBmTransaction txn = mock(IBmTransaction.class);
        Configuration cfg = mock(Configuration.class,
                withSettings().extraInterfaces(IBmObject.class));
        EList<Catalog> catalogs = new BasicEList<>();
        when(cfg.getCatalogs()).thenReturn(catalogs);
        when(txn.getTopObjectByFqn(eq("Configuration"))).thenReturn((IBmObject) cfg);
        when(txn.getTopObjectByFqn(eq("Catalog.New"))).thenReturn(null);

        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        MdObjectFactory factory = new MdObjectFactory(executor, cfgProvider, registry);
        String fqn = factory.create(project, "Catalog", "New", null, null);

        assertEquals("Catalog.New", fqn);
        assertEquals(1, catalogs.size());
        assertEquals("New", catalogs.get(0).getName());
        verify(txn).attachTopObject(any(IBmObject.class), eq("Catalog.New"));
    }

    @Test(expected = ToolException.class)
    public void createRejectsUnsupportedKind() throws Exception {
        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        IConfigurationProvider cfgProvider = mock(IConfigurationProvider.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");
        MdObjectFactory factory = new MdObjectFactory(executor, cfgProvider, registry);
        factory.create(project, "Frobnicator", "Foo", null, null);
    }

    @Test(expected = ToolException.class)
    public void createRejectsDuplicateName() throws Exception {
        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        IConfigurationProvider cfgProvider = mock(IConfigurationProvider.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");
        IBmTransaction txn = mock(IBmTransaction.class);
        Configuration cfg = mock(Configuration.class,
                withSettings().extraInterfaces(IBmObject.class));
        when(cfg.getCatalogs()).thenReturn(new BasicEList<>());
        when(txn.getTopObjectByFqn(eq("Configuration"))).thenReturn((IBmObject) cfg);
        // existing Catalog.Existing
        IBmObject existing = mock(IBmObject.class);
        when(txn.getTopObjectByFqn(eq("Catalog.Existing"))).thenReturn(existing);

        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        new MdObjectFactory(executor, cfgProvider, registry)
                .create(project, "Catalog", "Existing", null, null);
    }

    @Test
    public void createCommonModule_addsToCommonModulesContainment() throws Exception {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");
        BmPersistentExecutor executor = mock(BmPersistentExecutor.class);
        IConfigurationProvider cfgProvider = mock(IConfigurationProvider.class);

        IBmTransaction txn = mock(IBmTransaction.class);
        Configuration cfg = mock(Configuration.class,
                withSettings().extraInterfaces(IBmObject.class));
        EList commonModules = new BasicEList<>();
        when(cfg.getCommonModules()).thenReturn(commonModules);
        when(txn.getTopObjectByFqn(eq("Configuration"))).thenReturn((IBmObject) cfg);
        when(txn.getTopObjectByFqn(eq("CommonModule.Helper"))).thenReturn(null);

        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(2);
            return task.execute(txn);
        }).when(executor).execute(eq(project), any(), any());

        String fqn = new MdObjectFactory(executor, cfgProvider, registry)
                .create(project, "CommonModule", "Helper", null, null);
        assertEquals("CommonModule.Helper", fqn);
        assertEquals(1, commonModules.size());
    }
}
