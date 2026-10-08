package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.resource.IResourceStoreManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.wiring.ServiceAccess;
import jakarta.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;

/**
 * Чтения 1C:EDT для {@code get_infobase_sync_state} — без записи и без замков (кроме монитора делегата v2 на время
 * копии снимка, {@link EdtSyncSnapshot}).
 *
 * <p><b>Ветки EDT.</b> {@code getEqualityState} и {@code isConnected} — рефлексией: {@code isConnected} появился
 * только в {@code dt.platform.services.core} 21, прямой вызов дал бы {@code NoSuchMethodError} на 2023.x (как у
 * {@link InfobaseDeployer}); метода нет — значение {@code null} с причиной. {@code IDependentProject.getDependent} —
 * с фолбэком на тот же фильтр. API v2 — через {@link SyncV2}. Служба подписей ресурсов ({@code IResourceStoreManager})
 * — лениво и как {@code Object}, как у {@link ProjectFromInfobaseUpdater}: в Guice её нет. Сервис приложений EDT — по
 * имени класса из OSGi-реестра ({@link EdtApplicationState}).
 */
public class SyncStateProbe {

    private final IInfobaseSynchronizationManager sync;
    private final SyncV2 syncV2;
    private final IV8ProjectManager projects;
    private final Supplier<Object> resourcesLookup;
    private final Supplier<BundleContext> contexts;

    @Inject
    public SyncStateProbe(IInfobaseSynchronizationManager sync, SyncV2 syncV2, IV8ProjectManager projects) {
        this(sync, syncV2, projects, () -> ServiceAccess.get(IResourceStoreManager.class), SyncStateProbe::ownContext);
    }

    /**
     * @param resourcesLookup служба подписей ресурсов EDT (тестам — подставная)
     * @param contexts OSGi-контекст, через который берётся сервис приложений EDT (тестам — подставной)
     */
    public SyncStateProbe(IInfobaseSynchronizationManager sync, SyncV2 syncV2, IV8ProjectManager projects,
                          Supplier<Object> resourcesLookup, Supplier<BundleContext> contexts) {
        this.sync = sync;
        this.syncV2 = syncV2;
        this.projects = projects;
        this.resourcesLookup = resourcesLookup;
        this.contexts = contexts;
    }

    /**
     * Проекты расширений конфигурации — тот же список, что проверяет запуск клиента:
     * {@code IDependentProject.getDependent(конфигурация, getProjects(IExtensionProject.class))}.
     */
    public List<IProject> extensionProjects(IProject configuration) {
        Collection<IExtensionProject> extensions = projects.getProjects(IExtensionProject.class);
        try {
            return new ArrayList<>(IDependentProject.getDependent(configuration, extensions));
        } catch (LinkageError e) {
            return dependentOf(configuration, extensions);
        }
    }

    /** Фолбэк для ветки EDT без статического {@code getDependent}: тот же фильтр, что у него. */
    public static List<IProject> dependentOf(IProject configuration,
                                             Collection<? extends IExtensionProject> extensions) {
        List<IProject> out = new ArrayList<>();
        for (IExtensionProject extension : extensions) {
            if (extension != null && configuration.equals(extension.getParentProject())
                    && extension.getProject() != null) {
                out.add(extension.getProject());
            }
        }
        return out;
    }

    /** Состояние проекта с базой глазами EDT; что не получено — {@code null}, причина в {@code error}. */
    public ProjectSyncState read(IProject project, String kind, InfobaseReference infobase) {
        List<String> errors = new ArrayList<>();
        Object equality = call("getEqualityState", project, infobase, errors);
        Object connected = call("isConnected", project, infobase, errors);
        SyncV2.Dirtiness dirtiness = syncV2.projectDirty(project, infobase);
        if (dirtiness.failure() != null) errors.add("isProjectDirty: " + dirtiness.failure());
        String equalityState = equality instanceof Enum<?> constant ? constant.name()
            : equality == null ? null : equality.toString();
        return new ProjectSyncState(project.getName(), kind, equalityState,
            connected instanceof Boolean value ? value : null, dirtiness.dirty(),
            errors.isEmpty() ? null : String.join("; ", errors));
    }

    /** Метод {@code IInfobaseSynchronizationManager(IProject, InfobaseReference)} рефлексией. */
    private Object call(String method, IProject project, InfobaseReference infobase, List<String> errors) {
        try {
            Object value = IInfobaseSynchronizationManager.class
                .getMethod(method, IProject.class, InfobaseReference.class).invoke(sync, project, infobase);
            if (value == null) errors.add(method + ": EDT вернула null");
            return value;
        } catch (NoSuchMethodException e) {
            errors.add("в этой версии EDT нет IInfobaseSynchronizationManager." + method);
        } catch (InvocationTargetException e) {
            errors.add(method + ": " + McpJobs.describe(e.getCause() != null ? e.getCause() : e));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            errors.add(method + ": " + McpJobs.describe(e));
        }
        return null;
    }

    /**
     * Итог обновления приложения базы у самой EDT ({@link EdtApplicationState}, без записи); сервис после вызова
     * освобождается.
     */
    public EdtApplicationState.Result applicationState(IProject configuration, InfobaseReference infobase) {
        BundleContext context;
        try {
            context = contexts.get();
        } catch (RuntimeException | LinkageError e) {
            return new EdtApplicationState.Result(null, "нет OSGi-контекста бандла: " + McpJobs.describe(e));
        }
        if (context == null) return new EdtApplicationState.Result(null, "нет OSGi-контекста бандла");
        ServiceReference<?> reference;
        Object manager;
        try {
            reference = context.getServiceReference(EdtApplicationState.MANAGER);
            if (reference == null) {
                return new EdtApplicationState.Result(null,
                    "сервис приложений EDT (IApplicationManager) не зарегистрирован");
            }
            manager = context.getService(reference);
        } catch (RuntimeException e) {
            return new EdtApplicationState.Result(null, "сервис приложений EDT недоступен: " + McpJobs.describe(e));
        }
        try {
            return EdtApplicationState.query(manager, infobaseApplicationClass(context), configuration, infobase);
        } finally {
            if (manager != null) {
                try {
                    context.ungetService(reference);
                } catch (RuntimeException e) {
                    // бандл уже остановлен — освобождать нечего
                }
            }
        }
    }

    /** Текущие подписи ресурсов проекта — путь → подпись ({@code getEffectiveResourceMetadata}); иначе причина. */
    public record Current(Map<String, byte[]> files, String failure) {}

    public Current currentSignatures(IProject project) {
        Object service;
        try {
            service = resourcesLookup.get();
        } catch (RuntimeException | LinkageError e) {
            return new Current(null, "сервис ресурсов EDT недоступен: " + McpJobs.describe(e));
        }
        if (service == null) return new Current(null, "сервис ресурсов EDT недоступен");
        try {
            IV8Project v8 = projects.getProject(project);
            IDtProject dtProject = v8 == null ? null : v8.getDtProject();
            if (dtProject == null) {
                return new Current(null, "проект " + project.getName() + " не загружен в 1C:EDT как проект 1С");
            }
            Map<?, ?> metadata = ((IResourceStoreManager) service).getEffectiveResourceMetadata(dtProject);
            return metadata == null ? new Current(null, "EDT не отдала подписи ресурсов проекта")
                : new Current(EdtSyncSnapshot.signatures(metadata), null);
        } catch (InvocationTargetException e) {
            return new Current(null, "подписи ресурсов проекта не получить: "
                + McpJobs.describe(e.getCause() != null ? e.getCause() : e));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return new Current(null, "подписи ресурсов проекта не получить: " + McpJobs.describe(e));
        }
    }

    /** Снимок синхронизации пары в памяти EDT и на диске — {@link SyncV2#readSnapshot}. */
    public EdtSyncSnapshot.Read snapshot(IProject configuration, InfobaseReference infobase,
                                         Collection<String> extensionProjects) {
        return syncV2.readSnapshot(configuration, infobase, extensionProjects, true);
    }

    /**
     * Внутренний класс приложения-базы — загрузчиком бандла {@link EdtApplicationState#INFOBASES_BUNDLE}: пакет не
     * экспортируется. Нет бандла или класса — {@code null}, чего не хватает, скажет {@link EdtApplicationState#query}.
     */
    private static Class<?> infobaseApplicationClass(BundleContext context) {
        try {
            for (Bundle bundle : context.getBundles()) {
                if (EdtApplicationState.INFOBASES_BUNDLE.equals(bundle.getSymbolicName())) {
                    return bundle.loadClass(EdtApplicationState.INFOBASE_APPLICATION);
                }
            }
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            // класса нет в этой версии EDT
        }
        return null;
    }

    private static BundleContext ownContext() {
        Bundle bundle = FrameworkUtil.getBundle(SyncStateProbe.class);
        return bundle == null ? null : bundle.getBundleContext();
    }
}
