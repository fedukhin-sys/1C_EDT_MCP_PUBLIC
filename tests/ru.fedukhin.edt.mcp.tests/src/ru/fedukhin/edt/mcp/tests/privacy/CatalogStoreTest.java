package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import ru.fedukhin.edt.mcp.core.privacy.CatalogStore;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalog;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalogJson;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

public class CatalogStoreTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void unionsCatalogsAndCaches() throws Exception {
        Path a = tmp.newFile("a.json").toPath();
        Path b = tmp.newFile("b.json").toPath();
        Files.writeString(a, PiiCatalogJson.write(
            PiiCatalog.builder().object("Справочник.ФизическиеЛица", Sensitivity.PERSONAL).build(), "t"));
        Files.writeString(b, PiiCatalogJson.write(
            PiiCatalog.builder().object("Справочник.Контрагенты", Sensitivity.COUNTERPARTY).build(), "t"));

        CatalogStore store = new CatalogStore(() -> List.of(a, b));
        PiiCatalog c = store.current();
        assertEquals(Sensitivity.PERSONAL, c.forObject("Справочник.ФизическиеЛица"));
        assertEquals(Sensitivity.COUNTERPARTY, c.forObject("Справочник.Контрагенты"));
    }

    @Test public void missingFilesAreSkipped() {
        CatalogStore store = new CatalogStore(() -> List.of(Paths.get("nope-does-not-exist.json")));
        assertTrue(store.current().isEmpty());
    }

    @Test public void recomputesWhenPathSetChangesDespiteEqualMtime() throws Exception {
        Path a = tmp.newFile("a.json").toPath();
        Path b = tmp.newFile("b.json").toPath();
        Files.writeString(a, PiiCatalogJson.write(
            PiiCatalog.builder().object("Справочник.ФизическиеЛица", Sensitivity.PERSONAL).build(), "t"));
        Files.writeString(b, PiiCatalogJson.write(
            PiiCatalog.builder().object("Справочник.Контрагенты", Sensitivity.COUNTERPARTY).build(), "t"));

        // Force identical mtime on both files
        FileTime commonTime = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(a, commonTime);
        Files.setLastModifiedTime(b, commonTime);

        // Use mutable holder to switch which catalog is returned
        AtomicReference<List<Path>> ref = new AtomicReference<>(List.of(a));
        CatalogStore store = new CatalogStore(ref::get);

        // Verify we get catalog A
        PiiCatalog c1 = store.current();
        assertEquals(Sensitivity.PERSONAL, c1.forObject("Справочник.ФизическиеЛица"));
        assertEquals(Sensitivity.NONE, c1.forObject("Справочник.Контрагенты"));

        // Switch to catalog B (same mtime, same count) and verify it recomputes
        ref.set(List.of(b));
        PiiCatalog c2 = store.current();
        assertEquals(Sensitivity.NONE, c2.forObject("Справочник.ФизическиеЛица"));
        assertEquals(Sensitivity.COUNTERPARTY, c2.forObject("Справочник.Контрагенты"));
    }

    @Test public void recomputesWhenFileMtimeChanges() throws Exception {
        Path a = tmp.newFile("a.json").toPath();
        Files.writeString(a, PiiCatalogJson.write(
            PiiCatalog.builder().object("Справочник.ФизическиеЛица", Sensitivity.PERSONAL).build(), "t"));

        CatalogStore store = new CatalogStore(() -> List.of(a));

        // Verify initial catalog
        PiiCatalog c1 = store.current();
        assertEquals(Sensitivity.PERSONAL, c1.forObject("Справочник.ФизическиеЛица"));

        // Overwrite with different catalog and bump mtime
        Files.writeString(a, PiiCatalogJson.write(
            PiiCatalog.builder().object("Справочник.ФизическиеЛица", Sensitivity.COUNTERPARTY).build(), "t"));
        Files.setLastModifiedTime(a, FileTime.fromMillis(2_000_000L));

        // Verify it recomputes
        PiiCatalog c2 = store.current();
        assertEquals(Sensitivity.COUNTERPARTY, c2.forObject("Справочник.ФизическиеЛица"));
    }
}
