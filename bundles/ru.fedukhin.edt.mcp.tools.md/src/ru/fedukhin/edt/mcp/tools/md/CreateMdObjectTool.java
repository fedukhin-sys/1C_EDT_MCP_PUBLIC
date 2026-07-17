package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.BmSyncWaiter;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectKind;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;
import ru.fedukhin.edt.mcp.tools.md.internal.ModuleFileBootstrap;

/**
 * {@code create_md_object} — создаёт новый top-level MdObject в проекте.
 *
 * <p>Args: {@code { project, kind, name, synonym?, comment? }}.
 * <p>Result: {@code { fqn, kind, name, modulePath? }}.
 */
public final class CreateMdObjectTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdObjectFactory         factory;
    private final MdObjectRegistry        registry;
    private final ModuleFileBootstrap     moduleBootstrap;
    private final IBmModelManager         bmModelManager;
    private final MdoFileEditor           mdoEditor;

    @Inject
    public CreateMdObjectTool(MdObjectFactory factory, MdObjectRegistry registry,
                              ModuleFileBootstrap moduleBootstrap,
                              IBmModelManager bmModelManager,
                              MdoFileEditor mdoEditor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(),
                factory, registry, moduleBootstrap, bmModelManager, mdoEditor);
    }

    /** Test seam. */
    public CreateMdObjectTool(Supplier<IWorkspaceRoot> rootSupplier,
                              MdObjectFactory factory, MdObjectRegistry registry,
                              ModuleFileBootstrap moduleBootstrap,
                              IBmModelManager bmModelManager,
                              MdoFileEditor mdoEditor) {
        this.rootSupplier    = rootSupplier;
        this.factory         = factory;
        this.registry        = registry;
        this.moduleBootstrap = moduleBootstrap;
        this.bmModelManager  = bmModelManager;
        this.mdoEditor       = mdoEditor;
    }

    /** Test seam (no bmModelManager / mdoEditor — sync/post-fix are no-ops). */
    public CreateMdObjectTool(Supplier<IWorkspaceRoot> rootSupplier,
                              MdObjectFactory factory, MdObjectRegistry registry,
                              ModuleFileBootstrap moduleBootstrap) {
        this(rootSupplier, factory, registry, moduleBootstrap, null, null);
    }

    @Override public String name()        { return "create_md_object"; }
    @Override public String description() {
        return "Create a new top-level MdObject in a project. All data kinds are supported "
             + "(Catalog, Document, registers, Enum, Task, BusinessProcess, ChartOf*, "
             + "ExchangePlan, DocumentJournal, ...); CommonForm and CommonPicture cannot be "
             + "created from scratch — they need companion artifacts, use borrow_md_object.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> strType = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", strType);
        props.put("kind",    strType);
        props.put("name",    strType);
        props.put("synonym", strType);
        props.put("comment", strType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "kind", "name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String kind        = requireString(args, "kind");
        String name        = requireString(args, "name");
        String synonym     = args.get("synonym") instanceof String s ? s : null;
        String comment     = args.get("comment") instanceof String s ? s : null;

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        // Реестр знает и те kind'ы, что доступны только для чтения и заимствования: EMF-фабрика
        // создаст любой, но объект выйдет битым без сопутствующих артефактов (CommonForm без
        // Form.form, CommonPicture без картинки). Отбиваем до мутации BM — иначе в конфигурацию
        // попадёт мусор.
        MdObjectKind requested = registry.get(kind);
        if (requested != null && !requested.creatable()) {
            throw new ToolException("kind '" + kind + "' cannot be created from scratch; "
                + "it is supported for reading and for borrow_md_object only");
        }

        String fqn = factory.create(project, kind, name, synonym, comment);

        // Bug #3 (race-condition create→add_attribute): MdObjectFactory мутирует BM
        // через глобальный контекст, который сериализует .mdo асинхронно (Global Editing
        // Context.execute() возвращает до того, как .mdo появился на диске). Без явного
        // ожидания следующий немедленный add_attribute получал mdoFile.exists()=false.
        BmSyncWaiter.awaitModel(bmModelManager, project);

        MdObjectKind kindMeta = registry.get(kind);
        String mdoRel = null;
        boolean mdoOnDisk = true;
        if (kindMeta != null && kindMeta.folderName() != null) {
            mdoRel = "src/" + kindMeta.folderName() + "/" + name + "/" + name + ".mdo";
            // awaitStableFile, а не просто awaitFile: сериализатор EDT создаёт файл до того,
            // как допишет его, и следующий инструмент мог прочитать половину XML.
            mdoOnDisk = BmSyncWaiter.awaitStableFile(project, mdoRel, BmSyncWaiter.DEFAULT_MAX_MILLIS);
        }

        // Post-write cosmetic fixes: EDT XML-serializer пропускает default'ы enum'ов,
        // и в результате некоторые тэги в .mdo отсутствуют, хотя BM-модель их выставила.
        // Для InfReg дописываем <writeMode>Independent</writeMode>.
        if ("InformationRegister".equals(kind) && mdoEditor != null && mdoRel != null) {
            IFile mdoFile = project.getFile(mdoRel);
            if (mdoFile != null && mdoFile.exists()) {
                try { mdoEditor.ensureInfRegWriteMode(mdoFile); }
                catch (ToolException ignored) { /* best-effort, deploy не страдает */ }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn",  fqn);
        result.put("kind", kind);
        result.put("name", name);

        if (kindMeta != null && kindMeta.hasModule()) {
            String modulePath = moduleBootstrap.ensureModuleBsl(project, kindMeta, fqn);
            result.put("modulePath", modulePath);
        }

        // Паттерн BUG-18: молчаливый «успех» после неудавшегося ожидания посылает агента
        // делать следующий вызов по пути, которого ещё нет.
        if (!mdoOnDisk) {
            result.put("warning", "object was created in the BM model, but '" + mdoRel
                + "' had not appeared on disk within " + BmSyncWaiter.DEFAULT_MAX_MILLIS
                + " ms — EDT serialises .mdo asynchronously. A follow-up tool call may report "
                + "the file as missing; retry it, or re-read the object with get_md_object.");
        }

        return result;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
