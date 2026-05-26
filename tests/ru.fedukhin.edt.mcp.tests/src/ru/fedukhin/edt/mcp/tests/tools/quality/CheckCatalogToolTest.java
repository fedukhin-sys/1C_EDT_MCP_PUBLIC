package ru.fedukhin.edt.mcp.tests.tools.quality;

import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.quality.CheckCatalogTool;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckCatalog;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckEntry;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CheckCatalogToolTest {

    @Test public void listsAndForwardsFilters() throws Exception {
        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.list("naming", "warning", "v8codestyle")).thenReturn(List.of(
                new CheckEntry("c.a", "A", "desc-a", "warning", "v8codestyle", "BSL", true)));

        CheckCatalogTool tool = new CheckCatalogTool(catalog);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) tool.call(Map.of(
                "filter",   "naming",
                "severity", "warning",
                "source",   "v8codestyle"));

        verify(catalog).list("naming", "warning", "v8codestyle");
        assertEquals(1, result.size());
        assertEquals("c.a", result.get(0).get("checkId"));
        assertFalse(result.get(0).containsKey("description"));
    }

    @Test public void omittedArgsBecomeNull() throws Exception {
        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.list(null, null, null)).thenReturn(List.of());

        CheckCatalogTool tool = new CheckCatalogTool(catalog);
        tool.call(Map.of());

        verify(catalog).list(null, null, null);
    }
}
