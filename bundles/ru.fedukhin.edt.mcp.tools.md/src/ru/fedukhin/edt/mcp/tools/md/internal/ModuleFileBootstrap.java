package ru.fedukhin.edt.mcp.tools.md.internal;

import java.io.ByteArrayInputStream;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import jakarta.inject.Inject;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Возвращает путь к «дефолтному» .bsl-модулю MdObject после {@code create_md_object}.
 *
 * <p>Поведение зависит от {@link MdObjectKind#requireEmptyModuleFile()}:
 * <ul>
 *   <li>{@code true} (CommonModule) — пустой {@code Module.bsl} создаётся физически,
 *       включая родительские папки. Без файла EDT не распознаёт CommonModule.</li>
 *   <li>{@code false} (Document/Catalog/DataProcessor/Report/Register-kinds) — путь
 *       вычисляется, но файл не создаётся. Модель агента создаёт его позже при первом
 *       write'е (например, через {@code add_extension_method_override}).</li>
 * </ul>
 *
 * <p>Идемпотентно: если файл уже есть — путь возвращается без перезаписи.
 *
 * <p>Запускается ВНЕ BM-транзакции — это workspace I/O.
 */
public final class ModuleFileBootstrap {

    private final MdObjectRegistry registry;

    @Inject public ModuleFileBootstrap(MdObjectRegistry registry) {
        this.registry = registry;
    }

    /** Test seam (без registry — фоллбек на жёсткий CommonModule layout). */
    public ModuleFileBootstrap() {
        this(new MdObjectRegistry());
    }

    /**
     * Совместимый с предыдущим API метод: kind определяется по префиксу FQN.
     * @param fqn — "{@code Kind.Name}", kind определяется по {@code MdObjectRegistry}
     */
    public String ensureModuleBsl(IProject project, String fqn) throws ToolException {
        int dot = fqn == null ? -1 : fqn.indexOf('.');
        if (dot < 0 || dot == fqn.length() - 1) {
            throw new ToolException("invalid fqn: '" + fqn + "'");
        }
        MdObjectKind kind = registry.get(fqn.substring(0, dot));
        return ensureModuleBsl(project, kind, fqn);
    }

    /**
     * Возвращает {@code src/<Folder>/<Name>/<moduleFileName>} для данного kind и FQN.
     * Для kind'ов с {@code requireEmptyModuleFile=true} дополнительно создаёт пустой файл
     * и родительские папки.
     *
     * @throws ToolException — если kind не имеет модуля, FQN невалидный, или I/O fail
     */
    public String ensureModuleBsl(IProject project, MdObjectKind kind, String fqn) throws ToolException {
        if (kind == null) {
            throw new ToolException("kind is null for fqn '" + fqn + "'");
        }
        if (!kind.hasModule()) {
            throw new ToolException("kind '" + kind.name() + "' has no module");
        }
        if (kind.folderName() == null || kind.moduleFileName() == null) {
            throw new ToolException("kind '" + kind.name() + "' missing folder/moduleFileName metadata");
        }
        int dot = fqn.indexOf('.');
        if (dot < 0 || dot == fqn.length() - 1) {
            throw new ToolException("invalid fqn: '" + fqn + "'");
        }
        String name = fqn.substring(dot + 1);
        String rel  = "src/" + kind.folderName() + "/" + name + "/" + kind.moduleFileName();

        if (!kind.requireEmptyModuleFile()) {
            // Lazy mode: модель агента создаст файл сама. Возвращаем только путь.
            return rel;
        }

        IFolder src           = project.getFolder("src");
        IFolder kindFolder    = src.getFolder(kind.folderName());
        IFolder moduleFolder  = kindFolder.getFolder(name);
        IFile   moduleFile    = moduleFolder.getFile(kind.moduleFileName());

        try {
            // Task #10 fix: BmPersistentExecutor пишет .mdo напрямую в FS через
            // глобальный контекст, минуя Eclipse-workspace. IFolder.exists() ещё
            // не видит этих новых файлов до refreshLocal.
            src.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            ensureFolder(src);
            ensureFolder(kindFolder);
            ensureFolder(moduleFolder);
            if (!moduleFile.exists()) {
                moduleFile.create(new ByteArrayInputStream(new byte[0]), /*force*/ false,
                                  new NullProgressMonitor());
            }
        } catch (CoreException e) {
            throw new ToolException("failed to create " + kind.moduleFileName()
                    + " for '" + fqn + "': " + e.getMessage());
        }
        return rel;
    }

    private static void ensureFolder(IFolder folder) throws CoreException {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) {
            ensureFolder(parent);
        }
        folder.create(/*force*/ false, /*local*/ true, new NullProgressMonitor());
    }
}
