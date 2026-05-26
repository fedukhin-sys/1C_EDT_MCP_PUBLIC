package ru.fedukhin.edt.mcp.tools.quality.internal;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/**
 * Resolves a per-check link into the «Стандарты разработки V8» document by reading the
 * HTML description shipped with the contributing bundle at
 * {@code check.descriptions/ru/<checkId>.html} and extracting the first
 * {@code https://its.1c.ru/db/v8std...} URL (see Spike 5).
 *
 * <p>Best-effort: ~50% of v8codestyle BSL checks have such a URL; the rest return empty.
 * Caches per-{@code checkId} so repeated {@code check_describe} calls don't re-read the
 * bundle resource.
 */
@Singleton
public class StandardReferenceResolver {

    static final Pattern V8STD_URL = Pattern.compile("https://its\\.1c\\.ru/db/v8std[^\"'\\s>]*");
    private static final Pattern HASH_SECTION = Pattern.compile("#content:(\\d+):hdoc(?::([\\w.]+))?");
    private static final Pattern PATH_SECTION = Pattern.compile("/content/(\\d+)/hdoc");

    private final Function<HtmlKey, String> htmlLoader;
    private final ConcurrentHashMap<String, Optional<StandardRef>> cache = new ConcurrentHashMap<>();

    /** Production constructor — loads from OSGi bundles. */
    @Inject
    public StandardReferenceResolver() {
        this(StandardReferenceResolver::loadFromBundle);
    }

    /** Test seam: {@code (bundle, checkId) -> html-or-null}. */
    public StandardReferenceResolver(Function<HtmlKey, String> htmlLoader) {
        this.htmlLoader = htmlLoader;
    }

    /**
     * Returns the {@link StandardRef} for a check, or {@link Optional#empty} if the
     * contributing bundle has no HTML description or the description contains no
     * {@code its.1c.ru/db/v8std} URL.
     */
    public Optional<StandardRef> resolve(String bundleSymbolicName, String checkId) {
        if (bundleSymbolicName == null || bundleSymbolicName.isBlank() ||
            checkId == null            || checkId.isBlank()) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(checkId, id -> parse(htmlLoader.apply(new HtmlKey(bundleSymbolicName, id))));
    }

    private static Optional<StandardRef> parse(String html) {
        if (html == null) return Optional.empty();
        Matcher m = V8STD_URL.matcher(html);
        if (!m.find()) return Optional.empty();
        String url = m.group();
        Matcher hash = HASH_SECTION.matcher(url);
        if (hash.find()) {
            return Optional.of(new StandardRef(StandardRef.DOCUMENT_TITLE, hash.group(1), hash.group(2)));
        }
        Matcher path = PATH_SECTION.matcher(url);
        if (path.find()) {
            return Optional.of(new StandardRef(StandardRef.DOCUMENT_TITLE, path.group(1), null));
        }
        return Optional.empty();
    }

    /** Production HTML loader: {@code Bundle.getResource("check.descriptions/ru/<id>.html")}. */
    private static String loadFromBundle(HtmlKey key) {
        Bundle bundle = Platform.getBundle(key.bundle());
        if (bundle == null) return null;
        URL url = bundle.getResource("check.descriptions/ru/" + key.checkId() + ".html");
        if (url == null) return null;
        try (InputStream is = url.openStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** Composite key for the HTML loader. Public so tests can construct it. */
    public record HtmlKey(String bundle, String checkId) {}
}
