package ru.fedukhin.edt.mcp.tests.tools.quality;

import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.quality.internal.StandardRef;
import ru.fedukhin.edt.mcp.tools.quality.internal.StandardReferenceResolver;
import ru.fedukhin.edt.mcp.tools.quality.internal.StandardReferenceResolver.HtmlKey;

import java.util.Optional;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StandardReferenceResolverTest {

    private static Function<HtmlKey, String> htmlLoader(String html) {
        return key -> html;
    }

    @Test public void parsesHashFragmentSectionOnly() {
        String html = "<html><body><h2>См.</h2>"
                    + "<a href=\"https://its.1c.ru/db/v8std#content:455:hdoc\">Раздел 455</a>"
                    + "</body></html>";
        StandardReferenceResolver resolver = new StandardReferenceResolver(htmlLoader(html));

        Optional<StandardRef> ref = resolver.resolve("com.e1c.v8codestyle.bsl", "module-structure-top-region");

        assertTrue(ref.isPresent());
        assertEquals(StandardRef.DOCUMENT_TITLE, ref.get().document());
        assertEquals("455", ref.get().section());
        assertEquals(null, ref.get().anchor());
    }

    @Test public void parsesHashFragmentWithAnchor() {
        String html = "<a href='https://its.1c.ru/db/v8std#content:499:hdoc:3.6'>x</a>";
        StandardReferenceResolver resolver = new StandardReferenceResolver(htmlLoader(html));

        Optional<StandardRef> ref = resolver.resolve("com.e1c.v8codestyle.bsl", "any");

        assertTrue(ref.isPresent());
        assertEquals("499", ref.get().section());
        assertEquals("3.6", ref.get().anchor());
    }

    @Test public void parsesPathStyleUrl() {
        String html = "<a href=\"https://its.1c.ru/db/v8std/content/436/hdoc\">y</a>";
        StandardReferenceResolver resolver = new StandardReferenceResolver(htmlLoader(html));

        Optional<StandardRef> ref = resolver.resolve("com.e1c.v8codestyle.bsl", "query-in-loop");

        assertTrue(ref.isPresent());
        assertEquals("436", ref.get().section());
        assertEquals(null, ref.get().anchor());
    }

    @Test public void takesFirstUrlOnly() {
        String html = "<a href='https://its.1c.ru/db/v8std#content:100:hdoc'>a</a>"
                    + "<a href='https://its.1c.ru/db/v8std#content:200:hdoc'>b</a>";
        StandardReferenceResolver resolver = new StandardReferenceResolver(htmlLoader(html));

        Optional<StandardRef> ref = resolver.resolve("x", "y");

        assertTrue(ref.isPresent());
        assertEquals("100", ref.get().section());
    }

    @Test public void noV8stdUrlReturnsEmpty() {
        String html = "<html>just text with https://example.com no v8std</html>";
        StandardReferenceResolver resolver = new StandardReferenceResolver(htmlLoader(html));

        Optional<StandardRef> ref = resolver.resolve("x", "y");

        assertTrue(ref.isEmpty());
    }

    @Test public void missingHtmlReturnsEmpty() {
        StandardReferenceResolver resolver = new StandardReferenceResolver(key -> null);

        Optional<StandardRef> ref = resolver.resolve("x", "y");

        assertTrue(ref.isEmpty());
    }

    @Test public void cachesResultPerCheckId() {
        int[] loads = { 0 };
        Function<HtmlKey, String> counting = key -> {
            loads[0]++;
            return "<a href='https://its.1c.ru/db/v8std#content:123:hdoc'>x</a>";
        };
        StandardReferenceResolver resolver = new StandardReferenceResolver(counting);

        resolver.resolve("b", "id-1");
        resolver.resolve("b", "id-1");
        resolver.resolve("b", "id-2");

        assertEquals(2, loads[0]);   // id-1 loaded once and cached, id-2 loaded once
    }
}
