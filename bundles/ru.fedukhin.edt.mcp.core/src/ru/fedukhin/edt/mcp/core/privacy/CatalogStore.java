package ru.fedukhin.edt.mcp.core.privacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

/** Union каталогов ПДн всех открытых проектов; кэш инвалидируется по суммарному mtime. */
public final class CatalogStore {

    public static final String CATALOG_REL = ".mcp/pii-catalog.json";

    private final Supplier<List<Path>> pathsSupplier;
    private volatile PiiCatalog cached;
    private volatile long cachedSignature = -1;

    /** Рабочий конструктор: собирает пути каталогов открытых проектов workspace. */
    public CatalogStore() {
        this(CatalogStore::workspaceCatalogPaths);
    }

    /** Тест-сим. */
    public CatalogStore(Supplier<List<Path>> pathsSupplier) {
        this.pathsSupplier = pathsSupplier;
    }

    public PiiCatalog current() {
        List<Path> paths = pathsSupplier.get();
        long sig = signature(paths);
        PiiCatalog c = cached;
        if (c != null && sig == cachedSignature) return c;
        List<PiiCatalog> parts = new ArrayList<>();
        for (Path p : paths) {
            if (!Files.isRegularFile(p)) continue;
            try {
                parts.add(PiiCatalogJson.read(Files.readString(p)));
            } catch (IOException | RuntimeException ignored) {
                // битый каталог пропускаем; движок останется fail-closed на regex-слое
            }
        }
        PiiCatalog merged = PiiCatalog.merge(parts);
        cached = merged;
        cachedSignature = sig;
        return merged;
    }

    public void write(String projectName, PiiCatalog c) throws IOException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        Path file = project.getLocation().toFile().toPath().resolve(CATALOG_REL);
        Files.createDirectories(file.getParent());
        Files.writeString(file, PiiCatalogJson.write(c, java.time.Instant.now().toString()));
        cachedSignature = -1; // инвалидация
    }

    private static long signature(List<Path> paths) {
        long s = 1;
        for (Path p : paths) {
            try { s = s * 31 + (Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() : 0); }
            catch (IOException e) { s = s * 31; }
            s = s * 31 + p.toString().hashCode();
        }
        return s * 31 + paths.size();
    }

    private static List<Path> workspaceCatalogPaths() {
        List<Path> out = new ArrayList<>();
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (p.isOpen() && p.getLocation() != null) {
                out.add(p.getLocation().toFile().toPath().resolve(CATALOG_REL));
            }
        }
        return out;
    }
}
