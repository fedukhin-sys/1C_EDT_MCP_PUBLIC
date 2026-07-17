package ru.fedukhin.edt.mcp.tests.tools.form;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.form.GetFormTool;
import ru.fedukhin.edt.mcp.tools.form.internal.FormFileReader;

/**
 * 2026-05-31 A8 — get_form читает Form.form напрямую с диска (через
 * {@link FormFileReader}). Тесты используют замоканные {@code IFile} с XML-стрингом
 * в качестве контентов — та же схема что в {@code ListAttributesToolTest}.
 */
public class GetFormToolTest {

    private static final String COMMON_FORM_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<form:Form xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
      + " xmlns:form=\"http://g5.1c.ru/v8/dt/form\">\n"
      + "  <title><key>ru</key><value>Селектор</value></title>\n"
      + "  <items xsi:type=\"form:FormField\">\n"
      + "    <name>Контрагент</name>\n"
      + "    <dataPath xsi:type=\"form:DataPath\">\n"
      + "      <segments>Объект</segments><segments>Контрагент</segments>\n"
      + "    </dataPath>\n"
      + "  </items>\n"
      + "  <items xsi:type=\"form:FormGroup\">\n"
      + "    <name>ГруппаДок</name>\n"
      + "    <items xsi:type=\"form:FormField\">\n"
      + "      <name>Номер</name>\n"
      + "    </items>\n"
      + "  </items>\n"
      + "  <attributes>\n"
      + "    <name>Период</name>\n"
      + "    <valueType><types>Number</types>"
      + "<numberQualifiers><precision>5</precision><scale>2</scale></numberQualifiers></valueType>\n"
      + "  </attributes>\n"
      + "  <formCommands>\n"
      + "    <name>Открыть</name>\n"
      + "    <title><key>ru</key><value>Открыть документ</value></title>\n"
      + "  </formCommands>\n"
      + "</form:Form>\n";

    private static final String EMPTY_FORM_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<form:Form xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
      + " xmlns:form=\"http://g5.1c.ru/v8/dt/form\">\n"
      + "</form:Form>\n";

    /** Сетап проект-мока: при запросе {@code formRel} возвращаем мок с {@code xml}. */
    private static IProject mockProjectWithForm(String formRel, String xml) throws Exception {
        IFile formFile = mock(IFile.class);
        when(formFile.exists()).thenReturn(true);
        when(formFile.getContents()).thenAnswer(inv ->
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        when(project.getFile(formRel)).thenReturn(formFile);
        // any other getFile call returns a not-exists file (e.g. Module.bsl probe)
        IFile missing = mock(IFile.class);
        when(missing.exists()).thenReturn(false);
        when(project.getFile(argThat((String s) -> s != null && !s.equals(formRel))))
                .thenReturn(missing);
        return project;
    }

    @Test
    public void commonForm_happyPath() throws Exception {
        IProject project = mockProjectWithForm("src/CommonForms/Selector/Form.form", COMMON_FORM_XML);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        GetFormTool tool = new GetFormTool(() -> root, new FormFileReader());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "CommonForm.Selector"));

        assertEquals("CommonForm.Selector", result.get("fqn"));
        assertEquals("Selector", result.get("name"));
        assertEquals("Селектор", result.get("title"));

        List<?> items = (List<?>) result.get("items");
        assertEquals(3, items.size()); // Контрагент + ГруппаДок + ГруппаДок/Номер
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals("Контрагент", first.get("name"));
        assertEquals("FormField", first.get("type"));
        assertEquals("Объект.Контрагент", first.get("dataPath"));
        Map<?, ?> nested = (Map<?, ?>) items.get(2);
        assertEquals("ГруппаДок/Номер", nested.get("path"));

        List<?> tree = (List<?>) result.get("tree");
        assertEquals(2, tree.size());
        Map<?, ?> group = (Map<?, ?>) tree.get(1);
        assertEquals("ГруппаДок", group.get("name"));
        List<?> kids = (List<?>) group.get("children");
        assertEquals(1, kids.size());
        assertEquals("Номер", ((Map<?, ?>) kids.get(0)).get("name"));

        List<?> attrs = (List<?>) result.get("attributes");
        assertEquals(1, attrs.size());
        Map<?, ?> a0 = (Map<?, ?>) attrs.get(0);
        assertEquals("Период", a0.get("name"));
        assertEquals("Number(5,2)", a0.get("type"));

        List<?> cmds = (List<?>) result.get("commands");
        assertEquals(1, cmds.size());
        Map<?, ?> c0 = (Map<?, ?>) cmds.get(0);
        assertEquals("Открыть", c0.get("name"));
        assertEquals("Открыть документ", c0.get("title"));

        assertEquals(false, result.get("hasModule")); // Module.bsl mock returns not exists
    }

    @Test
    public void catalogForm_pathDerivation() throws Exception {
        IProject project = mockProjectWithForm(
                "src/Catalogs/Goods/Forms/ListForm/Form.form", EMPTY_FORM_XML);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        GetFormTool tool = new GetFormTool(() -> root, new FormFileReader());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "Catalog.Goods.Form.ListForm"));

        assertEquals("ListForm", result.get("name"));
        assertEquals(0, ((List<?>) result.get("items")).size());
        assertEquals(0, ((List<?>) result.get("attributes")).size());
        assertEquals(0, ((List<?>) result.get("commands")).size());
    }

    /**
     * Наивная плюрализация {@code kind + "s"} давала «BusinessProcesss» и «ChartOfAccountss» —
     * get_form на этих kind'ах не находил Form.form. Папка должна браться из единого реестра.
     */
    @Test
    public void businessProcessForm_pathDerivation() throws Exception {
        IProject project = mockProjectWithForm(
                "src/BusinessProcesses/Согласование/Forms/ФормаЭлемента/Form.form", EMPTY_FORM_XML);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        GetFormTool tool = new GetFormTool(() -> root, new FormFileReader());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "BusinessProcess.Согласование.Form.ФормаЭлемента"));

        assertEquals("ФормаЭлемента", result.get("name"));
    }

    @Test
    public void chartOfAccountsForm_pathDerivation() throws Exception {
        IProject project = mockProjectWithForm(
                "src/ChartsOfAccounts/Основной/Forms/ФормаСписка/Form.form", EMPTY_FORM_XML);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        GetFormTool tool = new GetFormTool(() -> root, new FormFileReader());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "ChartOfAccounts.Основной.Form.ФормаСписка"));

        assertEquals("ФормаСписка", result.get("name"));
    }

    @Test(expected = ToolException.class)
    public void malformedFqn_throwsToolException() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(root.getProject("Demo")).thenReturn(project);

        new GetFormTool(() -> root, new FormFileReader())
                .call(Map.of("project", "Demo", "fqn", "Catalog.X"));
    }

    @Test(expected = ToolException.class)
    public void fileNotFound_throwsToolException() throws Exception {
        IFile missing = mock(IFile.class);
        when(missing.exists()).thenReturn(false);
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getFile(anyString())).thenReturn(missing);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        new GetFormTool(() -> root, new FormFileReader())
                .call(Map.of("project", "Demo", "fqn", "CommonForm.Missing"));
    }

    @Test
    public void moduleExists_hasModuleTrue() throws Exception {
        IFile formFile = mock(IFile.class);
        when(formFile.exists()).thenReturn(true);
        when(formFile.getContents()).thenAnswer(inv ->
                new ByteArrayInputStream(EMPTY_FORM_XML.getBytes(StandardCharsets.UTF_8)));
        IFile moduleFile = mock(IFile.class);
        when(moduleFile.exists()).thenReturn(true);

        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getFile("src/CommonForms/X/Form.form")).thenReturn(formFile);
        when(project.getFile("src/CommonForms/X/Module.bsl")).thenReturn(moduleFile);

        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        GetFormTool tool = new GetFormTool(() -> root, new FormFileReader());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
                Map.of("project", "Demo", "fqn", "CommonForm.X"));
        assertEquals(true, result.get("hasModule"));
        assertEquals("src/CommonForms/X/Module.bsl", result.get("modulePath"));
    }
}
