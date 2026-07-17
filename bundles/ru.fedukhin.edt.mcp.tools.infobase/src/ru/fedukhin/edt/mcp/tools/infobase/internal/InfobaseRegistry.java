package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferenceException;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com._1c.g5.v8.dt.platform.services.model.Section;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import ru.fedukhin.edt.mcp.core.api.ToolException;

public class InfobaseRegistry {

    private final IInfobaseManager manager;
    private final RuntimeCli runtimeCli;

    @Inject
    public InfobaseRegistry(IInfobaseManager manager, RuntimeCli runtimeCli) {
        this.manager = manager;
        this.runtimeCli = runtimeCli;
    }

    public List<InfobaseReference> listAll() {
        List<InfobaseReference> out = new ArrayList<>();
        for (Section s : manager.getAll()) {
            if (s instanceof InfobaseReference ref) {
                out.add(ref);
            }
        }
        return out;
    }

    public Optional<InfobaseReference> findByName(String name) { return manager.findInfobaseByName(name); }

    public Optional<InfobaseReference> findByUuid(UUID uuid) { return manager.findInfobaseByUuid(uuid); }

    /**
     * Create a FILE infobase: launch {@code 1cv8.exe CREATEINFOBASE} via {@link RuntimeCli},
     * then register the resulting {@link InfobaseReference} in EDT under {@code groupName}.
     * The {@code location} folder must NOT exist or must be empty.
     */
    public InfobaseReference createFileInfobase(String name, Path location, String version,
                                                String groupName, Duration timeout) throws ToolException {
        if (Files.exists(location)) {
            try (Stream<Path> children = Files.list(location)) {
                if (children.findAny().isPresent()) {
                    throw new ToolException("location '" + location + "' is not empty");
                }
            } catch (IOException e) {
                throw new ToolException("failed to inspect '" + location + "': " + e.getMessage(), e);
            }
        }

        runtimeCli.createFileInfobase(location, version, timeout);

        InfobaseReference ref = ModelFactory.eINSTANCE.createInfobaseReference();
        ref.setName(name);
        ref.setUuid(UUID.randomUUID());
        ref.setVersion(version);
        FileConnectionString cs = ModelFactory.eINSTANCE.createFileConnectionString();
        cs.setFile(location.toString());
        ref.setConnectionString(cs);
        ref.setShowInList(true);

        try {
            manager.add(ref, groupName);
        } catch (InfobaseReferenceException e) {
            throw new ToolException("failed to register infobase: " + e.getMessage(), e);
        }
        return ref;
    }

    public void delete(InfobaseReference ref, boolean deleteFiles) throws ToolException {
        try {
            manager.delete(ref);
        } catch (InfobaseReferenceException e) {
            throw new ToolException("infobase in use: " + e.getMessage(), e);
        }
        if (deleteFiles && ref.getConnectionString() instanceof FileConnectionString fcs) {
            Path dir = Path.of(fcs.getFile());
            if (Files.exists(dir)) {
                deleteRecursively(dir);
            }
        }
    }

    /**
     * Удаляет каталог ИБ и сообщает о том, что удалить не удалось.
     *
     * <p>Раньше здесь стоял {@code forEach(File::delete)}, а он возвращает {@code false} вместо
     * исключения: при занятом файле ИБ (открыт клиент 1С, работает отладчик) каталог оставался
     * на диске, а инструмент рапортовал успех. Обходим дерево «снизу вверх», ошибки копим и
     * поднимаем одним {@link ToolException} — так видно все остатки, а не только первый.
     */
    private static void deleteRecursively(Path dir) throws ToolException {
        List<String> failed = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException | RuntimeException e) {
                    failed.add(p.toString());
                }
            });
        } catch (IOException e) {
            throw new ToolException("failed to delete files: " + e.getMessage(), e);
        }
        if (!failed.isEmpty()) {
            throw new ToolException("failed to delete " + failed.size() + " file(s) under '"
                + dir + "' (infobase files may still be in use by a running 1С client): "
                + String.join(", ", failed));
        }
    }
}
