package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.util.Arrays;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;

public class MdObjectLocatorTest {

    private final MdObjectLocator locator = new MdObjectLocator();

    @Test
    public void findReturnsTopObjectWhenPresent() throws Exception {
        IBmTransaction txn = mock(IBmTransaction.class);
        IBmObject cat = mock(IBmObject.class);
        when(txn.getTopObjectByFqn(eq("Catalog.Goods"))).thenReturn(cat);
        IBmObject got = locator.findTop(txn, "Catalog.Goods", "Demo");
        assertSame(cat, got);
    }

    @Test
    public void findThrowsWhenMissing() {
        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn(anyString())).thenReturn(null);
        try {
            locator.findTop(txn, "Catalog.Missing", "Demo");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("Catalog.Missing"));
            assertTrue(e.getMessage().contains("Demo"));
        }
    }

    @Test
    public void findInListMatchesByName() throws Exception {
        CatalogAttribute code   = mock(CatalogAttribute.class);
        when(code.getName()).thenReturn("Code");
        CatalogAttribute descr  = mock(CatalogAttribute.class);
        when(descr.getName()).thenReturn("Description");
        EList<CatalogAttribute> list = new BasicEList<>(Arrays.asList(code, descr));

        Object found = locator.findInList(list, "Description");
        assertSame(descr, found);
    }

    @Test(expected = ToolException.class)
    public void findInListThrowsWhenMissing() throws Exception {
        EList<Object> empty = new BasicEList<>();
        locator.findInList(empty, "NoSuch");
    }
}
