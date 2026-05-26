package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.Arrays;
import java.util.List;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.junit.Test;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.AttributeFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.ParsedType;

public class AttributeFactoryTest {

    private final AttributeFactory f = new AttributeFactory();

    @Test
    public void addAttributeToCatalogUsesAttributesFeature() throws Exception {
        Catalog cat = mock(Catalog.class);
        EList<CatalogAttribute> attrs = new BasicEList<>();
        when(cat.getAttributes()).thenReturn(attrs);

        f.add(cat, "Catalog", "Attribute",
              "Article", List.of(ParsedType.string(25)),
              null, null);

        assertEquals(1, attrs.size());
        assertEquals("Article", attrs.get(0).getName());
    }

    @Test(expected = ToolException.class)
    public void addAttributeWithDuplicateNameThrows() throws Exception {
        Catalog cat = mock(Catalog.class);
        CatalogAttribute existing = mock(CatalogAttribute.class);
        when(existing.getName()).thenReturn("Article");
        when(cat.getAttributes()).thenReturn(new BasicEList<>(Arrays.asList(existing)));

        f.add(cat, "Catalog", "Attribute", "Article", List.of(ParsedType.string(25)), null, null);
    }

    @Test(expected = ToolException.class)
    public void addToRegisterWithoutRoleThrows() throws Exception {
        InformationRegister reg = mock(InformationRegister.class);
        f.add(reg, "InformationRegister", null,
              "AnyDim", List.of(ParsedType.date()), null, null);
    }

    @Test
    public void addDimensionToInformationRegister() throws Exception {
        InformationRegister reg = mock(InformationRegister.class);
        EList<InformationRegisterDimension> dims = new BasicEList<>();
        when(reg.getDimensions()).thenReturn(dims);

        f.add(reg, "InformationRegister", "Dimension",
              "Period", List.of(ParsedType.date()), null, null);

        assertEquals(1, dims.size());
    }

    @Test(expected = ToolException.class)
    public void addAttributeToCommonModuleThrows() throws Exception {
        f.add(mock(CommonModule.class), "CommonModule",
              "Attribute", "Any", List.of(ParsedType.bool()), null, null);
    }
}
