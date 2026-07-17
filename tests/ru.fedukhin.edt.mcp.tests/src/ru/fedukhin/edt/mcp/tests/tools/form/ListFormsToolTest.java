package ru.fedukhin.edt.mcp.tests.tools.form;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
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
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcessForm;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccountsForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.form.ListFormsTool;
import ru.fedukhin.edt.mcp.tools.form.internal.FormRegistry;
import ru.fedukhin.edt.mcp.tools.form.internal.MdObjectLocator;

public class ListFormsToolTest {

    /** Helper: set up IProject mock that exists and is open. */
    private static IProject projectMock(String name, IWorkspaceRoot root) {
        IProject p = mock(IProject.class);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(true);
        when(p.getName()).thenReturn(name);
        when(root.getProject(name)).thenReturn(p);
        return p;
    }

    /** Helper: execute the BM task callback inline. */
    private static void captureAndRunTask(IBmModelManager bm, IProject project, IBmTransaction txn) {
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(1);
            task.execute(txn);
            return null;
        }).when(bm).executeReadOnlyTask(eq(project), any());
    }

    // -----------------------------------------------------------------------
    // Test 1: list with parentFqn — returns the one form
    // -----------------------------------------------------------------------

    @Test
    public void listWithParentFqn_returnsSingleForm() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);

        IBmModelManager bm = mock(IBmModelManager.class);
        IConfigurationProvider cfgP = mock(IConfigurationProvider.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        // Catalog mock with one form
        CatalogForm form = mock(CatalogForm.class);
        when(form.getName()).thenReturn("ListForm");
        EList<CatalogForm> formList = new BasicEList<>(List.of(form));

        Catalog catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(catalog.getForms()).thenReturn(formList);
        when(txn.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject) catalog);

        captureAndRunTask(bm, project, txn);

        MdObjectLocator locator = new MdObjectLocator();
        ListFormsTool tool = new ListFormsTool(
                () -> root, bm, cfgP, locator, new FormRegistry());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "parentFqn", "Catalog.Goods"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forms = (List<Map<String, Object>>) result.get("forms");
        assertEquals(1, forms.size());
        assertEquals("ListForm",       forms.get(0).get("name"));
        assertEquals("Catalog.Goods.Form.ListForm", forms.get(0).get("fqn"));
        assertEquals("Catalog.Goods",  forms.get(0).get("parentFqn"));
    }

    // -----------------------------------------------------------------------
    // Test 2: list all (no parentFqn) — iterates Configuration catalogs
    // -----------------------------------------------------------------------

    @Test
    public void listAll_iteratesCatalogForms() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);

        IBmModelManager bm = mock(IBmModelManager.class);
        IConfigurationProvider cfgP = mock(IConfigurationProvider.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        CatalogForm form = mock(CatalogForm.class);
        when(form.getName()).thenReturn("ItemForm");
        EList<CatalogForm> formList = new BasicEList<>(List.of(form));

        Catalog catalog = mock(Catalog.class);
        when(catalog.getName()).thenReturn("Products");
        when(catalog.getForms()).thenReturn(formList);
        EList<Catalog> catalogs = new BasicEList<>(List.of(catalog));

        Configuration cfg = mock(Configuration.class, withSettings().extraInterfaces(IBmObject.class));
        when(cfg.getCatalogs()).thenReturn(catalogs);
        when(cfg.getDocuments()).thenReturn(new BasicEList<>());
        when(cfg.getInformationRegisters()).thenReturn(new BasicEList<>());
        when(cfg.getAccumulationRegisters()).thenReturn(new BasicEList<>());
        when(cfg.getDataProcessors()).thenReturn(new BasicEList<>());
        when(cfg.getReports()).thenReturn(new BasicEList<>());
        when(cfg.getCommonForms()).thenReturn(new BasicEList<>());
        when(txn.getTopObjectByFqn("Configuration")).thenReturn((IBmObject) cfg);

        captureAndRunTask(bm, project, txn);

        ListFormsTool tool = new ListFormsTool(
                () -> root, bm, cfgP, new MdObjectLocator(), new FormRegistry());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forms = (List<Map<String, Object>>) result.get("forms");
        assertEquals(1, forms.size());
        assertEquals("ItemForm",                    forms.get(0).get("name"));
        assertEquals("Catalog.Products.Form.ItemForm", forms.get(0).get("fqn"));
    }

    /**
     * list_forms без parentFqn обходил только 6 контейнеров + CommonForms, поэтому формы
     * бизнес-процессов, задач, планов счетов/видов расчёта/характеристик, планов обмена и
     * перечислений в выдачу не попадали вовсе.
     */
    @Test
    public void listAll_includesBusinessProcessAndChartOfAccountsForms() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);

        IBmModelManager bm = mock(IBmModelManager.class);
        IConfigurationProvider cfgP = mock(IConfigurationProvider.class);
        IBmTransaction txn = mock(IBmTransaction.class);

        BusinessProcessForm bpForm = mock(BusinessProcessForm.class);
        when(bpForm.getName()).thenReturn("ФормаЭлемента");
        BusinessProcess bp = mock(BusinessProcess.class);
        when(bp.getName()).thenReturn("Согласование");
        when(bp.getForms()).thenReturn(new BasicEList<>(List.of(bpForm)));

        ChartOfAccountsForm coaForm = mock(ChartOfAccountsForm.class);
        when(coaForm.getName()).thenReturn("ФормаСписка");
        ChartOfAccounts coa = mock(ChartOfAccounts.class);
        when(coa.getName()).thenReturn("Основной");
        when(coa.getForms()).thenReturn(new BasicEList<>(List.of(coaForm)));

        Configuration cfg = mock(Configuration.class, withSettings().extraInterfaces(IBmObject.class));
        when(cfg.getBusinessProcesses()).thenReturn(new BasicEList<>(List.of(bp)));
        when(cfg.getChartsOfAccounts()).thenReturn(new BasicEList<>(List.of(coa)));
        when(txn.getTopObjectByFqn("Configuration")).thenReturn((IBmObject) cfg);

        captureAndRunTask(bm, project, txn);

        ListFormsTool tool = new ListFormsTool(
                () -> root, bm, cfgP, new MdObjectLocator(), new FormRegistry());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of("project", "Demo"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forms = (List<Map<String, Object>>) result.get("forms");

        List<Object> fqns = forms.stream().map(f -> f.get("fqn")).toList();
        assertTrue("формы бизнес-процессов обязаны перечисляться, получено: " + fqns,
                fqns.contains("BusinessProcess.Согласование.Form.ФормаЭлемента"));
        assertTrue("формы планов счетов обязаны перечисляться, получено: " + fqns,
                fqns.contains("ChartOfAccounts.Основной.Form.ФормаСписка"));
    }

    // -----------------------------------------------------------------------
    // Test 3: parent kind without forms → ToolException
    // -----------------------------------------------------------------------

    @Test(expected = ToolException.class)
    public void parentKindWithoutForms_throwsToolException() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = projectMock("Demo", root);
        IBmModelManager bm = mock(IBmModelManager.class);
        IConfigurationProvider cfgP = mock(IConfigurationProvider.class);
        IBmTransaction txn = mock(IBmTransaction.class);
        captureAndRunTask(bm, project, txn);

        ListFormsTool tool = new ListFormsTool(
                () -> root, bm, cfgP, new MdObjectLocator(), new FormRegistry());

        // Role does not support forms
        tool.call(Map.of("project", "Demo", "parentFqn", "Role.FullAccess"));
    }
}
