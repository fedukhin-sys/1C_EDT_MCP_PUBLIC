package ru.fedukhin.edt.mcp.tests.tools.quality;

import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.quality.CheckDescribeTool;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckCatalog;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckEntry;
import ru.fedukhin.edt.mcp.tools.quality.internal.StandardRef;
import ru.fedukhin.edt.mcp.tools.quality.internal.StandardReferenceResolver;

import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CheckDescribeToolTest {

    @Test public void returnsEntryWithStandardRefWhenAvailable() throws Exception {
        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get("c.a")).thenReturn(Optional.of(
                new CheckEntry("c.a", "A", "desc-a", "error", "v8codestyle", "BSL", true)));
        StandardReferenceResolver resolver = mock(StandardReferenceResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(Optional.of(
                new StandardRef("Стандарты разработки V8", "455", "3.6")));

        CheckDescribeTool tool = new CheckDescribeTool(catalog, resolver);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of("checkId", "c.a"));

        assertEquals("c.a",     result.get("checkId"));
        assertEquals("desc-a",  result.get("description"));
        assertTrue(result.containsKey("standardRef"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) result.get("standardRef");
        assertEquals("455", ref.get("section"));
        assertEquals("3.6", ref.get("anchor"));
    }

    @Test public void omitsStandardRefWhenAbsent() throws Exception {
        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get("c.b")).thenReturn(Optional.of(
                new CheckEntry("c.b", "B", "desc-b", "warning", "edt", null, true)));
        StandardReferenceResolver resolver = mock(StandardReferenceResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(Optional.empty());

        CheckDescribeTool tool = new CheckDescribeTool(catalog, resolver);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(Map.of("checkId", "c.b"));

        assertFalse(result.containsKey("standardRef"));
    }

    @Test public void unknownCheckIdThrows() {
        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get("x")).thenReturn(Optional.empty());
        StandardReferenceResolver resolver = mock(StandardReferenceResolver.class);

        CheckDescribeTool tool = new CheckDescribeTool(catalog, resolver);
        assertThrows(ToolException.class, () -> tool.call(Map.of("checkId", "x")));
    }
}
