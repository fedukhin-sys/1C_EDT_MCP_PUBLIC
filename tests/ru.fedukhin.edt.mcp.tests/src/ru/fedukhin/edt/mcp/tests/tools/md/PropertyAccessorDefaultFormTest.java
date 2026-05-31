package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorForm;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentForm;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterForm;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.ReportForm;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;

/**
 * BUG-07: universal {@code defaultForm} property dispatches to the kind-specific
 * mdclass setter (DefaultObjectForm / DefaultForm / DefaultRecordForm /
 * DefaultListForm). SetMdPropertyTool resolves the FQN to the form EObject before
 * calling the accessor.
 */
public class PropertyAccessorDefaultFormTest {

    private PropertyAccessor accessor() {
        return new PropertyAccessor(mock(IV8ProjectManager.class));
    }

    @Test public void catalog_defaultForm_callsSetDefaultObjectForm() throws Exception {
        Catalog cat = mock(Catalog.class);
        CatalogForm form = mock(CatalogForm.class);
        accessor().set(cat, "Catalog", mock(IProject.class), "defaultForm", form);
        verify(cat).setDefaultObjectForm(form);
    }

    @Test public void document_defaultForm_callsSetDefaultObjectForm() throws Exception {
        Document doc = mock(Document.class);
        DocumentForm form = mock(DocumentForm.class);
        accessor().set(doc, "Document", mock(IProject.class), "defaultForm", form);
        verify(doc).setDefaultObjectForm(form);
    }

    @Test public void report_defaultForm_callsSetDefaultForm() throws Exception {
        Report report = mock(Report.class);
        ReportForm form = mock(ReportForm.class);
        accessor().set(report, "Report", mock(IProject.class), "defaultForm", form);
        verify(report).setDefaultForm(form);
    }

    @Test public void dataProcessor_defaultForm_callsSetDefaultForm() throws Exception {
        DataProcessor dp = mock(DataProcessor.class);
        DataProcessorForm form = mock(DataProcessorForm.class);
        accessor().set(dp, "DataProcessor", mock(IProject.class), "defaultForm", form);
        verify(dp).setDefaultForm(form);
    }

    @Test public void informationRegister_defaultForm_callsSetDefaultRecordForm() throws Exception {
        InformationRegister ir = mock(InformationRegister.class);
        InformationRegisterForm form = mock(InformationRegisterForm.class);
        accessor().set(ir, "InformationRegister", mock(IProject.class), "defaultForm", form);
        verify(ir).setDefaultRecordForm(form);
    }

    @Test(expected = ToolException.class)
    public void commonModule_defaultForm_rejected() throws Exception {
        CommonModule cm = mock(CommonModule.class);
        accessor().set(cm, "CommonModule", mock(IProject.class), "defaultForm",
                mock(BasicForm.class));
    }

    @Test(expected = ToolException.class)
    public void catalog_defaultForm_withStringValue_rejected() throws Exception {
        // PropertyAccessor expects a resolved EObject — the FQN-string path goes
        // through SetMdPropertyTool, which resolves to BmObject first.
        Catalog cat = mock(Catalog.class);
        accessor().set(cat, "Catalog", mock(IProject.class), "defaultForm",
                "Catalog.X.Form.Y");
    }

    @Test(expected = ToolException.class)
    public void accumulationRegister_defaultForm_withWrongFormType_rejected() throws Exception {
        // AccumulationRegister expects an AccumulationRegisterForm; passing a CatalogForm
        // should not match the AccumulationRegister.setDefaultListForm(AccumulationRegisterForm)
        // signature, so reflective dispatch fails.
        AccumulationRegister ar = mock(AccumulationRegister.class);
        CatalogForm wrongForm = mock(CatalogForm.class);
        accessor().set(ar, "AccumulationRegister", mock(IProject.class), "defaultForm", wrongForm);
    }
}
