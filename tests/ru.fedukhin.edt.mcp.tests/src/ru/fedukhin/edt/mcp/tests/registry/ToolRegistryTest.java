package ru.fedukhin.edt.mcp.tests.registry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.internal.registry.ToolRegistry;

public class ToolRegistryTest {

    @Test
    public void byName_returnsRegisteredTool() {
        IMcpTool t = mock(IMcpTool.class);
        when(t.name()).thenReturn("foo");
        ToolRegistry r = new ToolRegistry(Arrays.asList(t));
        assertTrue(r.byName("foo").isPresent());
        assertSame(t, r.byName("foo").get());
    }

    @Test
    public void byName_unknownReturnsEmpty() {
        ToolRegistry r = new ToolRegistry(Arrays.asList());
        assertFalse(r.byName("nope").isPresent());
    }

    @Test
    public void tools_returnsAll() {
        IMcpTool a = mock(IMcpTool.class); when(a.name()).thenReturn("a");
        IMcpTool b = mock(IMcpTool.class); when(b.name()).thenReturn("b");
        ToolRegistry r = new ToolRegistry(Arrays.asList(a, b));
        assertEquals(2, r.tools().size());
    }

    @Test
    public void duplicateNames_lastOneWinsAndIsLogged() {
        IMcpTool a1 = mock(IMcpTool.class); when(a1.name()).thenReturn("a");
        IMcpTool a2 = mock(IMcpTool.class); when(a2.name()).thenReturn("a");
        ToolRegistry r = new ToolRegistry(Arrays.asList(a1, a2));
        assertSame(a2, r.byName("a").get());
        assertEquals(1, r.tools().size());
    }
}
