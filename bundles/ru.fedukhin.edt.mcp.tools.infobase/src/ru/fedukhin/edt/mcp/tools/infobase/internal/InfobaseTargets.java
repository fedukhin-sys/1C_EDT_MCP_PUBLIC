package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/** Проект, связанная с ним база и его проекты расширений — то, над чем работают сценарии. */
public class InfobaseTargets {

    /** База операции и предупреждение сверки с ассоциацией (или {@code null}). */
    public record Target(InfobaseReference infobase, String warning) {}

    private final Supplier<IWorkspaceRoot> root;
    private final InfobaseRegistry registry;
    private final IInfobaseAssociationManager associations;
    private final IV8ProjectManager projects;

    @Inject
    public InfobaseTargets(InfobaseRegistry registry, IInfobaseAssociationManager associations,
                           IV8ProjectManager projects) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), registry, associations, projects);
    }

    public InfobaseTargets(Supplier<IWorkspaceRoot> root, InfobaseRegistry registry,
                           IInfobaseAssociationManager associations, IV8ProjectManager projects) {
        this.root = root;
        this.registry = registry;
        this.associations = associations;
        this.projects = projects;
    }

    public IProject openProject(String name) throws ToolException {
        IProject project = root.get().getProject(name);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + name + "' not open");
        }
        return project;
    }

    public IConfigurationProject configurationProject(IProject project) throws ToolException {
        IV8Project v8 = projects.getProject(project);
        if (v8 instanceof IConfigurationProject configuration) return configuration;
        String reason = v8 == null ? "EDT ещё не загрузила проект или это не проект 1С"
            : v8 instanceof IExtensionProject ? "это проект расширения" : "тип " + v8.getClass().getSimpleName();
        throw new ToolException("проект '" + project.getName() + "' не является проектом конфигурации (" + reason + ")");
    }

    /**
     * Проект конфигурации или расширения — только их содержимое «проект ← база» заменяет из базы (fix round 7,
     * R7-3). Состояние синхронизации любого другого зависимого проекта (например, проекта внешних отчётов и
     * обработок) EDT хранит у родительской конфигурации: сброс быстрой проверки и полная замена пришлись бы на
     * родителя.
     */
    public IV8Project configurationOrExtensionProject(IProject project) throws ToolException {
        IV8Project v8 = projects.getProject(project);
        if (v8 instanceof IConfigurationProject || v8 instanceof IExtensionProject) return v8;
        String kind = v8 == null ? "EDT ещё не загрузила проект или это не проект 1С"
            : v8 instanceof IExternalObjectProject ? "это проект внешних отчётов и обработок"
            : "тип " + v8.getClass().getSimpleName();
        throw new ToolException("проект '" + project.getName() + "' из базы не обновляется (" + kind + "): "
            + "только проект конфигурации или проект расширения");
    }

    /**
     * База операции. Имя задано — ищется в списке баз и сверяется с ассоциацией проекта
     * ({@link InfobaseGuard}); не задано — берётся база проекта по умолчанию.
     *
     * <p><b>Проект расширения без своей связи</b> следует за родительской конфигурацией: EDT 2026.1
     * связывает базу только с ОДНИМ проектом — с конфигурацией, и проект расширения с ней не связать
     * («Infobase X is already associated with project P»). Для такого проекта база по умолчанию — база
     * родителя, а явное имя сверяется со связями родителя: иначе примитив на проекте расширения
     * отказывал бы («не связан ни с одной информационной базой») или предупреждал зря.
     */
    public Target resolve(IProject project, String infobaseName, boolean allowForeign) throws ToolException {
        IProject parent = parentOfExtensionWithoutOwnAssociation(project);
        if (infobaseName != null) {
            InfobaseReference ref = registry.findByName(infobaseName)
                .orElseThrow(() -> new ToolException("infobase '" + infobaseName + "' not found"));
            if (parent == null) {
                Optional<String> warning = InfobaseGuard.check(project.getName(), infobaseName,
                    InfobaseGuard.associatedNames(associations, project), allowForeign);
                return new Target(ref, warning.orElse(null));
            }
            String prefix = followsParent(project, parent) + ": ";
            try {
                Optional<String> warning = InfobaseGuard.check(parent.getName(), infobaseName,
                    InfobaseGuard.associatedNames(associations, parent), allowForeign);
                return new Target(ref, warning.map(w -> prefix + w).orElse(null));
            } catch (ToolException e) {
                throw new ToolException(prefix + e.getMessage(), e);
            }
        }
        IInfobaseAssociation association = association(parent == null ? project : parent);
        InfobaseReference ref = association == null ? null : association.getDefaultInfobase();
        if (ref == null) {
            if (parent != null) {
                throw new ToolException(followsParent(project, parent) + ", а она не связана ни с одной "
                    + "информационной базой: свяжите проект '" + parent.getName() + "' вызовом associate_infobase "
                    + "или передайте infobase");
            }
            throw new ToolException("проект '" + project.getName() + "' не связан ни с одной информационной "
                + "базой: свяжите его вызовом associate_infobase или передайте infobase");
        }
        return new Target(ref, null);
    }

    private static String followsParent(IProject extension, IProject parent) {
        return "проект расширения '" + extension.getName() + "' своей связи с базой не имеет и следует за "
            + "родительской конфигурацией '" + parent.getName() + "'";
    }

    /**
     * Родительская конфигурация проекта расширения, у которого своей связи с базой нет; {@code null} —
     * проект конфигурации, проект расширения со своей связью или модель проекта ещё не загружена
     * (тогда работает прежняя логика по связям самого проекта). Прочитать связи расширения не удалось —
     * считаем, что своей связи нет.
     */
    private IProject parentOfExtensionWithoutOwnAssociation(IProject project) {
        if (!(projects.getProject(project) instanceof IExtensionProject extension)) return null;
        IProject parent = extension.getParentProject();
        if (parent == null) return null;
        Optional<Set<String>> own = InfobaseGuard.associatedNames(associations, project);
        return own.isPresent() && !own.get().isEmpty() ? null : parent;
    }

    public List<String> associatedInfobases(IProject project) throws ToolException {
        IInfobaseAssociation association = association(project);
        List<String> names = new ArrayList<>();
        Collection<InfobaseReference> refs = association == null ? null : association.getInfobases();
        if (refs != null) {
            for (InfobaseReference ref : refs) {
                if (ref != null && ref.getName() != null) names.add(ref.getName());
            }
        }
        return names;
    }

    /**
     * Принадлежит ли проект расширения базе: связан с ней явно ИЛИ своей связи не имеет вовсе (тогда он
     * следует за родительской конфигурацией — в EDT 2026.1 проект расширения с базой родителя и не
     * связать: база связывается только с одним проектом, с конфигурацией). Связан только с другими
     * базами — не принадлежит. Прочитать связи не удалось — считаем, что своей связи нет.
     */
    public boolean belongsTo(IProject extensionProject, InfobaseReference infobase) {
        Optional<Set<String>> names = InfobaseGuard.associatedNames(associations, extensionProject);
        return names.isEmpty() || names.get().isEmpty() || names.get().contains(infobase.getName());
    }

    /** Связывает проект с базой без синхронизации — как {@code associate_infobase}. */
    public void associate(IProject project, InfobaseReference infobase, boolean setDefault) throws ToolException {
        try {
            associations.associate(project, infobase, InfobaseAssociationSettings.notSynchronized());
            if (setDefault) {
                associations.setDefaultInfobase(project, infobase, InfobaseAssociationContext.empty());
            }
        } catch (InfobaseAssociationException e) {
            throw new ToolException("не удалось связать проект " + project.getName() + " с базой "
                + infobase.getName() + ": " + e.getMessage(), e);
        }
    }

    /** Проекты расширений, чей родитель — {@code configuration}. */
    public List<IExtensionProject> extensionProjects(IProject configuration) {
        List<IExtensionProject> out = new ArrayList<>();
        for (IExtensionProject ext : projects.getProjects(IExtensionProject.class)) {
            if (ext != null && configuration.equals(ext.getParentProject())) out.add(ext);
        }
        return out;
    }

    /** Проект расширения по имени расширения в базе, без учёта регистра. */
    public Optional<IExtensionProject> extensionProject(IProject configuration, String extensionName) {
        return extensionProjects(configuration).stream()
            .filter(ext -> extensionName.equalsIgnoreCase(extensionName(ext)))
            .findFirst();
    }

    /** Имя расширения — имя конфигурации проекта расширения; {@code null}, если модель ещё не загружена. */
    public static String extensionName(IExtensionProject ext) {
        Configuration configuration = ext.getConfiguration();
        return configuration == null ? null : configuration.getName();
    }

    private IInfobaseAssociation association(IProject project) throws ToolException {
        try {
            return associations.getAssociation(project).orElse(null);
        } catch (InfobaseAssociationException e) {
            throw new ToolException("не удалось прочитать связи проекта " + project.getName()
                + " с базами: " + e.getMessage(), e);
        }
    }
}
