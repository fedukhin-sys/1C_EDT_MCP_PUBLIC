package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.AddAttributeTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringParser;

/**
 * Все supported kinds (Catalog/Document/InfReg/AccReg) роутятся через
 * {@link MdoFileEditor} (DOM). Тесты проверяют что:
 *  - non-Register kind → {@code addMdObjectAttribute}
 *  - Register kind + role → {@code addRegisterAttribute} с правильной ролью и списком типов
 *  - Register kind без role → ToolException
 */
public class AddAttributeToolTest {

    private final MdObjectRegistry registry = new MdObjectRegistry();
    private final TypeStringParser parser   = new TypeStringParser();

    private IProject mockProject() {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IFile mdoFile = mock(IFile.class);
        when(mdoFile.exists()).thenReturn(true);
        when(project.getFile(anyString())).thenReturn(mdoFile);
        return project;
    }

    @Test
    public void addStringAttributeToCatalog_happyPath() throws Exception {
        IProject project = mockProject();
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        MdoFileEditor mdoEditor = mock(MdoFileEditor.class);
        AddAttributeTool tool = new AddAttributeTool(() -> root, registry, parser, mdoEditor);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "fqn", "Catalog.Goods",
                "name", "Article",
                "type", "String(25)"));

        assertEquals("Catalog.Goods", result.get("fqn"));
        assertEquals("Article",       result.get("attributeName"));
        assertEquals("Attribute",     result.get("role"));

        ArgumentCaptor<MdoFileEditor.MdAttributeSpec> cap =
                ArgumentCaptor.forClass(MdoFileEditor.MdAttributeSpec.class);
        verify(mdoEditor).addMdObjectAttribute(any(), cap.capture());
        assertEquals("Article",   cap.getValue().name());
        assertEquals("String(25)", cap.getValue().type());
    }

    @Test(expected = ToolException.class)
    public void registerWithoutRole_throwsToolException() throws Exception {
        IProject project = mockProject();
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        AddAttributeTool tool = new AddAttributeTool(() -> root, registry, parser, mock(MdoFileEditor.class));
        tool.call(Map.of(
                "project", "Demo",
                "fqn", "InformationRegister.Sales",
                "name", "Period",
                "type", "Date"));
    }

    @Test
    public void addDimensionToAccReg_writesMultiRefTypes() throws Exception {
        // Bug #2 fix: REF-Dimension на Register раньше уходил в AttributeFactory (BM-path),
        // который для REF-типов писал пустой <type/>. Теперь идём через DOM с
        // <types>DocumentRef.X</types><types>DocumentRef.Y</types>.
        IProject project = mockProject();
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        MdoFileEditor mdoEditor = mock(MdoFileEditor.class);
        AddAttributeTool tool = new AddAttributeTool(() -> root, registry, parser, mdoEditor);

        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "fqn", "AccumulationRegister.TestReg",
                "name", "Документ",
                "role", "Dimension",
                "type", List.of("DocumentRef.Order", "DocumentRef.Invoice")));

        assertEquals("Dimension", result.get("role"));

        ArgumentCaptor<MdoFileEditor.RegisterAttrSpec> cap =
                ArgumentCaptor.forClass(MdoFileEditor.RegisterAttrSpec.class);
        verify(mdoEditor).addRegisterAttribute(any(), cap.capture());
        assertEquals("Dimension", cap.getValue().role());
        assertEquals("Документ",  cap.getValue().name());
        assertEquals(List.of("DocumentRef.Order", "DocumentRef.Invoice"), cap.getValue().types());
    }

    @Test
    public void addNumberResourceToInfReg() throws Exception {
        IProject project = mockProject();
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        MdoFileEditor mdoEditor = mock(MdoFileEditor.class);
        AddAttributeTool tool = new AddAttributeTool(() -> root, registry, parser, mdoEditor);

        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of(
                "project", "Demo",
                "fqn", "InformationRegister.Sales",
                "name", "Amount",
                "role", "Resource",
                "type", "Number(15,2)"));

        assertEquals("Resource", result.get("role"));
        ArgumentCaptor<MdoFileEditor.RegisterAttrSpec> cap =
                ArgumentCaptor.forClass(MdoFileEditor.RegisterAttrSpec.class);
        verify(mdoEditor).addRegisterAttribute(any(), cap.capture());
        assertEquals("Resource",         cap.getValue().role());
        assertEquals(List.of("Number(15,2)"), cap.getValue().types());
    }
}
