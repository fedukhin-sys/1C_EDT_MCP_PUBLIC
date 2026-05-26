package ru.fedukhin.edt.mcp.tests.tools.quality;

import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckCategory;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IContributor;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckCatalog;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckEntry;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckSource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class CheckCatalogTest {

    // -------- CheckSource classification --------

    @Test public void sourceFromBundleSymbolicName_v8codestyle() {
        assertEquals("v8codestyle", CheckSource.fromBundle("com.e1c.v8codestyle.bsl"));
        assertEquals("v8codestyle", CheckSource.fromBundle("com.e1c.v8codestyle.md"));
        assertEquals("v8codestyle", CheckSource.fromBundle("com.e1c.v8codestyle.form"));
    }

    @Test public void sourceFromBundleSymbolicName_dtCheck() {
        assertEquals("dt.check", CheckSource.fromBundle("com.e1c.dt.check.md"));
        assertEquals("dt.check", CheckSource.fromBundle("com.e1c.dt.check.form"));
    }

    @Test public void sourceFromBundleSymbolicName_edt() {
        // E1C-namespaced bundles
        assertEquals("edt", CheckSource.fromBundle("com.e1c.g5.v8.dt.bsl.check"));
        assertEquals("edt", CheckSource.fromBundle("com.e1c.g5.v8.dt.md.check"));
        assertEquals("edt", CheckSource.fromBundle("com.e1c.g5.v8.dt.md.extension.check"));
        assertEquals("edt", CheckSource.fromBundle("com.e1c.g5.dt.core.legacy"));
        // 1C-namespaced (underscore prefix) bundles seen in live smoke 2026-05-15
        assertEquals("edt", CheckSource.fromBundle("com._1c.g5.v8.dt.bsl"));
        assertEquals("edt", CheckSource.fromBundle("com._1c.g5.v8.dt.md"));
        assertEquals("edt", CheckSource.fromBundle("com._1c.g5.v8.dt.md.extension"));
        assertEquals("edt", CheckSource.fromBundle("com._1c.g5.v8.dt.bp.scheme"));
    }

    @Test public void sourceFromBundleSymbolicName_other() {
        assertEquals("other", CheckSource.fromBundle("com.example.thirdparty.check"));
        assertEquals("other", CheckSource.fromBundle(null));
        assertEquals("other", CheckSource.fromBundle(""));
    }

    // -------- CheckCatalog snapshot + filter --------

    @Test public void snapshotIsCachedAfterFirstCall() {
        ICheckRepository repo = mock(ICheckRepository.class);
        ICheckDescription d1 = mockDescription("check.a", "Check A", "Description of A", IssueSeverity.MAJOR);
        // Evaluate d1.getId() before entering when() to avoid Mockito "unfinished stubbing" pitfall
        // (calling a stub inside Map.of() would record it as the last invocation, not getChecksWithDescriptions).
        CheckUid key1 = d1.getId();
        when(repo.getChecksWithDescriptions()).thenReturn(Map.of(key1, d1));

        CheckCatalog catalog = new CheckCatalog(() -> repo,
                id -> "com.e1c.g5.v8.dt.bsl.check",
                uid -> null);

        List<CheckEntry> first  = catalog.list(null, null, null);
        List<CheckEntry> second = catalog.list(null, null, null);

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        verify(repo, times(1)).getChecksWithDescriptions();
    }

    @Test public void filterByFilterSubstringMatchesTitleAndDescription() {
        CheckCatalog catalog = catalogOf(
                entry("check.module-naming",    "Module naming",    "Names of modules",          "warning", "v8codestyle"),
                entry("check.exception-format", "Exception format", "Format of raise statements","error",   "v8codestyle"),
                entry("check.unused-variable",  "Unused variable",  "Variable never read",       "info",    "edt"));

        List<CheckEntry> hits = catalog.list("naming", null, null);

        assertEquals(1, hits.size());
        assertEquals("check.module-naming", hits.get(0).checkId());
    }

    @Test public void filterBySeverity() {
        CheckCatalog catalog = catalogOf(
                entry("a", "A", "", "warning", "edt"),
                entry("b", "B", "", "error",   "edt"),
                entry("c", "C", "", "error",   "edt"));

        List<CheckEntry> hits = catalog.list(null, "error", null);

        assertEquals(2, hits.size());
        assertEquals(Set.of("b", "c"),
                hits.stream().map(CheckEntry::checkId).collect(Collectors.toSet()));
    }

    @Test public void filterBySource() {
        CheckCatalog catalog = catalogOf(
                entry("a", "A", "", "warning", "v8codestyle"),
                entry("b", "B", "", "warning", "dt.check"),
                entry("c", "C", "", "warning", "edt"));

        List<CheckEntry> hits = catalog.list(null, null, "v8codestyle");

        assertEquals(1, hits.size());
        assertEquals("a", hits.get(0).checkId());
    }

    @Test public void getByIdReturnsFullEntry() {
        CheckCatalog catalog = catalogOf(
                entry("check.a", "Check A", "Description of A", "warning", "edt"));

        Optional<CheckEntry> got = catalog.get("check.a");

        assertTrue(got.isPresent());
        assertEquals("Description of A", got.get().description());
    }

    @Test public void getByIdMissingReturnsEmpty() {
        CheckCatalog catalog = catalogOf(
                entry("check.a", "Check A", "Description of A", "warning", "edt"));

        assertTrue(catalog.get("check.unknown").isEmpty());
    }

    // -------- categoryResolver (production behaviour) --------

    @Test public void categoryResolverReturnsParentTitle() {
        ICheckRepository repo = mock(ICheckRepository.class);
        CheckUid parentUid = new CheckUid("cat.bsl", "");
        // ICheckRepository.getDescription is statically typed to return ICheckDescription,
        // but at runtime EDT returns ICheckCategory nodes via the same call. Mockito's
        // strict return-type check forbids that unless the mock also implements
        // ICheckDescription — give it the extra interface via withSettings().
        ICheckCategory cat = mock(ICheckCategory.class,
                withSettings().extraInterfaces(ICheckDescription.class));
        when(cat.getTitle()).thenReturn("BSL");
        when(repo.getDescription(parentUid)).thenReturn((ICheckDescription) cat);

        ICheckDescription d = mock(ICheckDescription.class);
        when(d.getId()).thenReturn(new CheckUid("check.a", ""));
        when(d.getTitle()).thenReturn("Check A");
        when(d.getDescription()).thenReturn("");
        when(d.getSeverity()).thenReturn(IssueSeverity.MINOR);
        when(d.getParentId()).thenReturn(parentUid);
        when(d.isEnabled()).thenReturn(true);

        Map<CheckUid, ICheckDescription> map = new LinkedHashMap<>();
        map.put(new CheckUid("check.a", ""), d);
        when(repo.getChecksWithDescriptions()).thenReturn(map);

        CheckCatalog catalog = new CheckCatalog(
                () -> repo,
                id -> "com.e1c.g5.v8.dt.bsl.check",
                CheckCatalog.repoCategoryResolver(repo));

        List<CheckEntry> hits = catalog.list(null, null, null);
        assertEquals(1, hits.size());
        assertEquals("BSL", hits.get(0).category());
    }

    @Test public void categoryResolverReturnsNullWhenParentMissing() {
        ICheckRepository repo = mock(ICheckRepository.class);
        when(repo.getDescription(any(CheckUid.class))).thenReturn(null);

        Function<CheckUid, String> resolver = CheckCatalog.repoCategoryResolver(repo);

        assertNull(resolver.apply(new CheckUid("orphan", "")));
    }

    @Test public void categoryResolverReturnsNullWhenParentIsNotCategory() {
        ICheckRepository repo = mock(ICheckRepository.class);
        ICheckDescription notACategory = mock(ICheckDescription.class);   // wrong type
        when(repo.getDescription(any(CheckUid.class))).thenReturn(notACategory);

        Function<CheckUid, String> resolver = CheckCatalog.repoCategoryResolver(repo);

        assertNull(resolver.apply(new CheckUid("weird", "")));
    }

    @Test public void categoryResolverReturnsNullWhenGetDescriptionThrows() {
        // Live smoke 2026-05-15: ICheckRepository.getDescription throws
        // "Check with id '...' not found" for unknown UIDs rather than returning null.
        ICheckRepository repo = mock(ICheckRepository.class);
        when(repo.getDescription(any(CheckUid.class)))
                .thenThrow(new RuntimeException("Check with id 'CP:com._1c.g5.v8.dt.md' not found"));

        Function<CheckUid, String> resolver = CheckCatalog.repoCategoryResolver(repo);

        assertNull(resolver.apply(new CheckUid("orphan", "")));
    }

    // -------- bundleResolver from extension registry --------

    @Test public void bundleResolverFromExtensionRegistry() {
        // Build a fake IExtensionRegistry with one extension contributing two checks
        // from the v8codestyle.bsl bundle and one from edt.
        IConfigurationElement el1 = mock(IConfigurationElement.class);
        when(el1.getAttribute("class"))
                .thenReturn("com.e1c.v8codestyle.internal.bsl.ExecutableExtensionFactory:"
                          + "com.e1c.v8codestyle.bsl.check.QueryInLoopCheck");
        IConfigurationElement el2 = mock(IConfigurationElement.class);
        when(el2.getAttribute("class"))
                .thenReturn("com.e1c.v8codestyle.bsl.check.ModuleStructureCheck");

        IContributor v8csContributor = mock(IContributor.class);
        when(v8csContributor.getName()).thenReturn("com.e1c.v8codestyle.bsl");
        IExtension ext1 = mock(IExtension.class);
        when(ext1.getContributor()).thenReturn(v8csContributor);
        when(ext1.getConfigurationElements()).thenReturn(new IConfigurationElement[] { el1, el2 });

        IConfigurationElement el3 = mock(IConfigurationElement.class);
        when(el3.getAttribute("class"))
                .thenReturn("com.e1c.g5.v8.dt.bsl.check.UnusedVariableCheck");
        IContributor edtContributor = mock(IContributor.class);
        when(edtContributor.getName()).thenReturn("com.e1c.g5.v8.dt.bsl.check");
        IExtension ext2 = mock(IExtension.class);
        when(ext2.getContributor()).thenReturn(edtContributor);
        when(ext2.getConfigurationElements()).thenReturn(new IConfigurationElement[] { el3 });

        IExtensionPoint xp = mock(IExtensionPoint.class);
        when(xp.getExtensions()).thenReturn(new IExtension[] { ext1, ext2 });
        IExtensionRegistry registry = mock(IExtensionRegistry.class);
        when(registry.getExtensionPoint("com.e1c.g5.v8.dt.check.checks")).thenReturn(xp);

        Function<String, String> resolver = CheckCatalog.extensionRegistryBundleResolver(registry);

        // Lookup by fully-qualified class name (the form Marker.getSourceType() carries):
        assertEquals("com.e1c.v8codestyle.bsl",
                resolver.apply("com.e1c.v8codestyle.bsl.check.QueryInLoopCheck"));
        assertEquals("com.e1c.v8codestyle.bsl",
                resolver.apply("com.e1c.v8codestyle.bsl.check.ModuleStructureCheck"));
        assertEquals("com.e1c.g5.v8.dt.bsl.check",
                resolver.apply("com.e1c.g5.v8.dt.bsl.check.UnusedVariableCheck"));

        // Unknown id → empty string (so CheckSource.fromBundle returns OTHER).
        assertEquals("", resolver.apply("not.registered.SomeCheck"));
        assertEquals("", resolver.apply(null));
    }

    @Test public void bundleResolverNullExtensionPoint() {
        IExtensionRegistry registry = mock(IExtensionRegistry.class);
        when(registry.getExtensionPoint("com.e1c.g5.v8.dt.check.checks")).thenReturn(null);

        Function<String, String> resolver = CheckCatalog.extensionRegistryBundleResolver(registry);

        assertEquals("", resolver.apply("anything"));
    }

    // -------- helpers --------

    private static CheckEntry entry(String id, String title, String desc, String sev, String source) {
        return new CheckEntry(id, title, desc, sev, source, "category-x", true);
    }

    /** Builds a catalog whose snapshot is the given list of entries; bypasses the live repository. */
    private static CheckCatalog catalogOf(CheckEntry... entries) {
        ICheckRepository repo = mock(ICheckRepository.class);
        Map<CheckUid, ICheckDescription> snapshot = new LinkedHashMap<>();
        for (CheckEntry e : entries) {
            ICheckDescription d = mockDescription(e.checkId(), e.title(), e.description(), reverseSeverity(e.severity()));
            snapshot.put(d.getId(), d);
        }
        when(repo.getChecksWithDescriptions()).thenReturn(snapshot);

        // Bundle resolver: derive from the entry's source.
        Map<String, String> bundleByCheckId = new HashMap<>();
        for (CheckEntry e : entries) {
            bundleByCheckId.put(e.checkId(), switch (e.source()) {
                case "v8codestyle" -> "com.e1c.v8codestyle.bsl";
                case "dt.check"    -> "com.e1c.dt.check.md";
                case "edt"         -> "com.e1c.g5.v8.dt.bsl.check";
                default            -> "com.example.x";
            });
        }

        // Category resolver: simple constant "category-x" matching the entry helper.
        return new CheckCatalog(() -> repo,
                id -> bundleByCheckId.getOrDefault(id, "com.example.x"),
                uid -> "category-x");
    }

    /** Inverse of IssueSeverityName.fromEdt for test scaffolding. */
    private static IssueSeverity reverseSeverity(String name) {
        return switch (name) {
            case "error"   -> IssueSeverity.MAJOR;
            case "warning" -> IssueSeverity.MINOR;
            default        -> IssueSeverity.TRIVIAL;
        };
    }

    private static ICheckDescription mockDescription(String checkId, String title, String description,
                                                     IssueSeverity sev) {
        ICheckDescription d = mock(ICheckDescription.class);
        CheckUid uid = mock(CheckUid.class);
        when(uid.toString()).thenReturn(checkId);
        when(d.getId()).thenReturn(uid);
        when(d.getTitle()).thenReturn(title);
        when(d.getDescription()).thenReturn(description);
        when(d.getSeverity()).thenReturn(sev);
        when(d.isEnabled()).thenReturn(true);
        when(d.getParentId()).thenReturn(null);   // category resolver gets uid but doesn't need it for tests
        return d;
    }
}
