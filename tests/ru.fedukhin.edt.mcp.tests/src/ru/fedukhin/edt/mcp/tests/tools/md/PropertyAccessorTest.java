package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.common.util.EMap;
import org.junit.Test;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;

public class PropertyAccessorTest {

    @Test
    public void setCommentOnCatalog() throws Exception {
        Catalog cat = mock(Catalog.class);
        IProject project = mock(IProject.class);
        new PropertyAccessor(mock(IV8ProjectManager.class))
                .set(cat, "Catalog", project, "comment", "Hello");
        verify(cat).setComment("Hello");
    }

    @Test
    public void setSynonymOnCatalog_writesToEMapWithFallbackLang() throws Exception {
        Catalog cat = mock(Catalog.class);
        EMap<String, String> map = new BasicEMap<>();
        when(cat.getSynonym()).thenReturn(map);
        IProject project = mock(IProject.class);

        // IV8ProjectManager returns null project — falls back to "ru"
        IV8ProjectManager pm = mock(IV8ProjectManager.class);
        when(pm.getProject(project)).thenReturn(null);

        new PropertyAccessor(pm).set(cat, "Catalog", project, "synonym", "Справочник");
        assertEquals("Справочник", map.get("ru"));
    }

    @Test
    public void setServerOnCommonModule() throws Exception {
        CommonModule cm = mock(CommonModule.class);
        new PropertyAccessor(mock(IV8ProjectManager.class))
                .set(cm, "CommonModule", mock(IProject.class), "server", true);
        verify(cm).setServer(true);
    }

    @Test
    public void setClientOnCommonModule_callsSetClientManagedApplication() throws Exception {
        CommonModule cm = mock(CommonModule.class);
        new PropertyAccessor(mock(IV8ProjectManager.class))
                .set(cm, "CommonModule", mock(IProject.class), "client", true);
        verify(cm).setClientManagedApplication(true);
    }

    @Test
    public void setServerCallsAllowedOnCommonModule_callsSetServerCall() throws Exception {
        CommonModule cm = mock(CommonModule.class);
        new PropertyAccessor(mock(IV8ProjectManager.class))
                .set(cm, "CommonModule", mock(IProject.class), "serverCallsAllowed", false);
        verify(cm).setServerCall(false);
    }

    @Test(expected = ToolException.class)
    public void setServerOnCatalogRejected() throws Exception {
        Catalog cat = mock(Catalog.class);
        new PropertyAccessor(mock(IV8ProjectManager.class))
                .set(cat, "Catalog", mock(IProject.class), "server", true);
    }

    @Test(expected = ToolException.class)
    public void setUnknownPropertyRejected() throws Exception {
        Catalog cat = mock(Catalog.class);
        new PropertyAccessor(mock(IV8ProjectManager.class))
                .set(cat, "Catalog", mock(IProject.class), "frobnicate", "x");
    }

    @Test(expected = ToolException.class)
    public void setCommentWithWrongTypeRejected() throws Exception {
        Catalog cat = mock(Catalog.class);
        new PropertyAccessor(mock(IV8ProjectManager.class))
                .set(cat, "Catalog", mock(IProject.class), "comment", 42);
    }

    @Test(expected = ToolException.class)
    public void setTypeOnAttributeRejected() throws Exception {
        Catalog cat = mock(Catalog.class);
        new PropertyAccessor(mock(IV8ProjectManager.class))
                .set(cat, "Attribute", mock(IProject.class), "type", "String");
    }
}
