package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;

/**
 * Сброс быстрой проверки EDT «база не менялась» для пары проект — база (живой прогон 1.24.0, F4) — рефлексией
 * по внутреннему делегату синхронизации v2. Вызывается только из {@link SyncV2#forceRecheck}; в Guice не
 * биндится.
 *
 * <p><b>Что сбрасываем.</b> Забирая изменения базы, EDT сначала спрашивает у агента конфигуратора
 * «идентификатор поколения данных» базы и сравнивает его с записанным в состоянии синхронизации
 * ({@code AbstractInfobaseSynchronizationFlow.checkGenerationChanged}); совпал — {@code NO_CHANGES}, конфигурация
 * базы с проектом не сравнивается вовсе. Загрузка {@code .dt}/{@code .cfe} этот идентификатор не меняет (на живом
 * прогоне у четырёх разных баз из наших {@code .dt} он был один), поэтому после неё проект молча оставался
 * прежним. Пустой идентификатор — «неизвестно» самой EDT ({@code InfobaseSyncState.UNDEFINED}, помощник CI
 * {@code generateIBSyncState}): с ним проверка поколения всегда «изменилось», и EDT сравнивает базу с записанным
 * состоянием (платформенный {@code -getChanges}) — но только если её «ворота» открыты: пока у соединения агента
 * конфигуратора не взведён {@code externalChangesCheckRequired}, EDT отвечает {@code NO_CHANGES}, не спрашивая
 * базу вовсе, и сброс поколения до неё не доходит (ворота открывает закрытие сеанса агента,
 * {@code ThickClientOps.releaseDesignerSession}). С {@code fullReload} записанный {@code ConfigDumpInfo} пары ещё
 * и откладывается — переносится рядом ({@code <имя>.mcp-prev}; fix round 9, рекомендация 1): без него у потока нет
 * сведений о синхронизации, и EDT выгружает конфигурацию целиком и сливает с {@code fullReload = true} — ровно путь
 * ни разу не синхронизированного проекта; полной перезагрузки не случилось — {@link #restore} вернёт его на место.
 *
 * <p><b>Как.</b> Шаг в шаг как публичный {@code InfobaseSynchronizationStateManagerDelegate.forceEdtSynchronization}
 * (по байткоду): {@code calculateStoreProject} → {@code initLockStatesIfAbsent} →
 * {@code findOrCreateProjectInfobaseSynchronizationStateHolder} → {@code lockInfobaseState} → {@code synchronized}
 * по полю {@code lock} делегата → изменение состояния держателя и {@code writeEdtSyncrhonizationState} его хранилища
 * → {@code unlockInfobaseState}. Отличий два. Замок базы берётся ДО {@code try} — не взяли, не снимаем
 * ({@code unlockInfobaseState} владельца не проверяет и снял бы чужой замок; у EDT вызов замка внутри
 * {@code try}). Все члены классов (делегата, держателя, состояния — по объявленному типу поля {@code state}, —
 * хранилища) ищутся, а природа проекта спрашивается ДО замка — сразу после {@code calculateStoreProject}, до
 * первого вызова, который у EDT что-то меняет: под замком остаются только вызовы, а нехватку члена в другой
 * версии EDT видно, не взяв замка. Меняется только идентификатор поколения (отметка времени, UUID конфигурации,
 * подписи, версии и состояния расширений — прежние); пустое или отсутствующее — сбрасывать нечего, ничего не
 * пишем, общий статический {@code InfobaseSyncState.UNDEFINED} не трогаем никогда.
 *
 * <p><b>Чужое состояние (fix round 7, R7-3).</b> {@code calculateStoreProject} отдаёт родителя любого зависимого
 * проекта — не только расширения, но и, например, проекта внешних отчётов и обработок. Проект, чьё состояние EDT
 * хранит у другого проекта, а сам он не расширение, — «не сделано», EDT не тронута: иначе сброс (и перенос
 * {@code ConfigDumpInfo}) пришёлся бы на родительскую конфигурацию.
 *
 * <p>Классы EDT внутренние: ни одной статической ссылки на них, в сигнатурах только JDK и публичный API. Сбой —
 * не исключение, а {@code Recheck(false, причина)}.
 */
final class EdtSyncStateRecheck {

    /** Природа проекта расширения — как в {@code forceEdtSynchronization} ({@code IProject.hasNature}). */
    static final String EXTENSION_NATURE = "com._1c.g5.v8.dt.core.V8ExtensionNature";

    private EdtSyncStateRecheck() {}

    static SyncV2.Recheck run(Object delegate, IProject project, InfobaseReference infobase, boolean fullReload) {
        Method unlockState;
        Method lockState;
        Object lock;
        Object holder;
        Members members;
        boolean extension;
        String projectName;
        try {
            Method calculateStoreProject = declaredMethod(delegate.getClass(), "calculateStoreProject", IProject.class);
            Method initLockStates = declaredMethod(delegate.getClass(), "initLockStatesIfAbsent", IProject.class,
                InfobaseReference.class);
            Method findOrCreateHolder = declaredMethod(delegate.getClass(),
                "findOrCreateProjectInfobaseSynchronizationStateHolder", IProject.class, InfobaseReference.class);
            lockState = declaredMethod(delegate.getClass(), "lockInfobaseState", InfobaseReference.class);
            unlockState = declaredMethod(delegate.getClass(), "unlockInfobaseState", InfobaseReference.class);
            lock = declaredField(delegate.getClass(), "lock").get(delegate);
            Object storeProject = calculateStoreProject.invoke(delegate, project);
            // Вид проекта — сразу, до первого вызова, который у EDT что-то меняет (initLockStatesIfAbsent,
            // findOrCreate…): не удалось его узнать или проект чужой — EDT не тронута вовсе.
            extension = project.hasNature(EXTENSION_NATURE);
            projectName = project.getName();
            if (!extension && !project.equals(storeProject)) {
                return SyncV2.Recheck.failed(storedElsewhere(projectName, storeProject));
            }
            initLockStates.invoke(delegate, storeProject, infobase);
            holder = findOrCreateHolder.invoke(delegate, storeProject, infobase);
            if (holder == null || lock == null) {
                return SyncV2.Recheck.failed("EDT не отдала состояние синхронизации проекта с этой базой");
            }
            Field stateField = declaredField(holder.getClass(), "state");
            Object store = declaredField(holder.getClass(), "synchronizationStore").get(holder);
            if (store == null) return SyncV2.Recheck.failed("у состояния синхронизации EDT нет хранилища");
            members = Members.of(stateField, store);
        } catch (ReflectiveOperationException | CoreException | RuntimeException | LinkageError e) {
            return failure(e);
        }
        try {
            lockState.invoke(delegate, infobase);
        } catch (InvocationTargetException e) {
            return SyncV2.Recheck.failed("не удалось взять замок состояния синхронизации базы: "
                + McpJobs.describe(cause(e)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return failure(e);
        }
        SyncV2.Recheck result = null;
        try {
            synchronized (lock) {
                result = reset(members, holder, projectName, extension, fullReload);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            result = failure(e);
        } finally {
            String unlockFailure = unlock(unlockState, delegate, infobase);
            if (unlockFailure != null) {
                // M2 (fix round 8): ConfigDumpInfo мог уже быть отложен — сведения о переносе правдивы и при
                // «не сделано», без них копию нечем было бы вернуть (fix round 9).
                result = SyncV2.Recheck.failed(result != null && !result.done()
                    ? result.reason() + "; " + unlockFailure : unlockFailure,
                    result != null ? result.moved() : null);
            }
        }
        return result;
    }

    /**
     * Члены держателя, состояния ({@code InfobaseSyncState}) и хранилища EDT — найдены до замка по объявленному
     * типу поля {@code state} держателя и классу хранилища; под замком остаются только вызовы. Только типы JDK:
     * класс в Guice не биндится, но и внутренние классы EDT он не называет.
     */
    private record Members(Field state, Object store, Constructor<?> newState, Method timestamp,
                           Method configurationUuid, Method resourceMetadata, Method resourceVersions,
                           Method generationId, Method extensionStates, Object undefined, Method write,
                           Method mainDumpInfo, Method extensionDumpInfo) {

        static Members of(Field state, Object store) throws ReflectiveOperationException {
            Class<?> type = state.getType();
            Class<?> storeType = store.getClass();
            return new Members(state, store,
                type.getConstructor(long.class, String.class, Map.class, Map.class, String.class),
                type.getMethod("getTimestamp"), type.getMethod("getConfigurationUUID"),
                type.getMethod("getEdtResourceMetadata"), type.getMethod("getPlatformResourceVersions"),
                type.getMethod("getGenerationId"), type.getMethod("getExtensionSyncStates"),
                staticFieldOrNull(type, "UNDEFINED"),
                storeType.getMethod("writeEdtSyncrhonizationState", type),
                storeType.getMethod("getMainPlatformResourceVersionsPath"),
                storeType.getMethod("getExtensionPlatformResourceVersionsPath", String.class));
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> extensionsOf(Object syncState) throws ReflectiveOperationException {
            return (Map<Object, Object>) extensionStates.invoke(syncState);
        }

        /** Копия состояния с пустым идентификатором поколения: {@code (timestamp, UUID, подписи, версии, "")}. */
        Object withoutGeneration(Object syncState) throws ReflectiveOperationException {
            return newState.newInstance(timestamp.invoke(syncState), configurationUuid.invoke(syncState),
                resourceMetadata.invoke(syncState), resourceVersions.invoke(syncState), "");
        }
    }

    /**
     * Под замками: у проекта конфигурации — новое состояние держателя с пустым идентификатором поколения (и
     * прежними состояниями расширений, как в {@code finishSynchronizationFlow}); у проекта расширения — новое
     * состояние этого расширения в карте состояний (ключ — имя проекта, как у EDT). Что-то поменялось — запись
     * хранилища. {@code fullReload} — перенос записанного {@code ConfigDumpInfo} пары в сторону.
     */
    private static SyncV2.Recheck reset(Members m, Object holder, String projectName, boolean extension,
                                        boolean fullReload) throws ReflectiveOperationException {
        Object state = m.state().get(holder);
        boolean usable = state != null && state != m.undefined();
        boolean changed = false;
        if (extension) {
            if (usable) {
                Map<Object, Object> extensions = m.extensionsOf(state);
                Object own = extensions.get(projectName);
                if (own != null && own != m.undefined() && notBlank(m.generationId().invoke(own))) {
                    extensions.put(projectName, m.withoutGeneration(own));
                    changed = true;
                }
            }
        } else if (usable && notBlank(m.generationId().invoke(state))) {
            Object fresh = m.withoutGeneration(state);
            m.extensionsOf(fresh).putAll(m.extensionsOf(state));
            m.state().set(holder, fresh);
            changed = true;
        }
        if (changed) {
            try {
                m.write().invoke(m.store(), m.state().get(holder));
            } catch (InvocationTargetException e) {
                // В памяти EDT состояние уже сброшено — ближайшее обновление в этом сеансе всё равно сравнит базу.
                return SyncV2.Recheck.failed("EDT не записала состояние синхронизации: " + McpJobs.describe(cause(e)));
            }
        }
        return fullReload ? moveDumpInfoAside(m, projectName, extension) : SyncV2.Recheck.ok();
    }

    /** Суффикс отложенной копии {@code ConfigDumpInfo}: рядом с оригиналом, EDT её не читает. */
    static final String ASIDE_SUFFIX = ".mcp-prev";

    /**
     * Полная перезагрузка: записанный {@code ConfigDumpInfo} пары ОТКЛАДЫВАЕТСЯ — переносится рядом
     * ({@code <имя>.mcp-prev}, прежняя копия перезаписывается), а не удаляется (fix round 9, рекомендация 1): EDT его
     * не видит и выгружает конфигурацию целиком, а если полной перезагрузки не случится, {@link #restore} вернёт его
     * ({@link SyncV2#settleDumpInfo}). Файла не было — откладывать нечего, и «перенесено» не сообщается (m3). Путь
     * спрашивается только здесь (fix round 7, R7-4): у EDT {@code getExtensionPlatformResourceVersionsPath} создаёт
     * каталог {@code <хранилище>/ext/<проект>}, а сбой пути уже после сброса — не «ничего не сделано», а «полная
     * перезагрузка не подготовлена».
     */
    private static SyncV2.Recheck moveDumpInfoAside(Members m, String projectName, boolean extension) {
        String notPrepared = "быстрая проверка сброшена, но полная перезагрузка не подготовлена: ";
        Path dumpInfo;
        try {
            dumpInfo = (Path) (extension ? m.extensionDumpInfo().invoke(m.store(), projectName)
                : m.mainDumpInfo().invoke(m.store()));
        } catch (InvocationTargetException e) {
            return SyncV2.Recheck.failed(notPrepared + "EDT не отдала путь записанного ConfigDumpInfo ("
                + McpJobs.describe(cause(e)) + ") — обновление сравнит базу с записанным состоянием");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return SyncV2.Recheck.failed(notPrepared + "путь записанного ConfigDumpInfo не получить ("
                + McpJobs.describe(e) + ") — обновление сравнит базу с записанным состоянием");
        }
        if (dumpInfo == null || !Files.exists(dumpInfo)) return SyncV2.Recheck.ok();
        Path aside = dumpInfo.resolveSibling(dumpInfo.getFileName() + ASIDE_SUFFIX);
        try {
            Files.move(dumpInfo, aside, StandardCopyOption.REPLACE_EXISTING);
            return SyncV2.Recheck.ok(new SyncV2.DumpInfoMove(dumpInfo, aside));
        } catch (IOException | RuntimeException e) {
            return SyncV2.Recheck.failed(notPrepared + "не удалось отложить записанный ConfigDumpInfo ("
                + McpJobs.describe(e) + ") — обновление сравнит базу с записанным состоянием");
        }
    }

    /**
     * Возвращает отложенный {@code ConfigDumpInfo} на место (fix round 9, рекомендация 1) — под теми же замками EDT,
     * что и перенос: {@code calculateStoreProject} → {@code initLockStatesIfAbsent} → {@code lockInfobaseState} (до
     * {@code try}; не взяли — не возвращаем, копия цела) → {@code synchronized(lock)} → {@code unlockInfobaseState}.
     * Если EDT, пока шёл забор, записала свой {@code ConfigDumpInfo} — оставляем её, нашу копию удаляем. Замок после
     * этого не снялся — судьба файла прежняя, а причина идёт замечанием в {@code reason}. Не бросает.
     */
    static SyncV2.DumpInfoSettlement restore(Object delegate, IProject project, InfobaseReference infobase,
                                             SyncV2.DumpInfoMove moved) {
        Method lockState;
        Method unlockState;
        Object lock;
        try {
            Method calculateStoreProject = declaredMethod(delegate.getClass(), "calculateStoreProject", IProject.class);
            Method initLockStates = declaredMethod(delegate.getClass(), "initLockStatesIfAbsent", IProject.class,
                InfobaseReference.class);
            lockState = declaredMethod(delegate.getClass(), "lockInfobaseState", InfobaseReference.class);
            unlockState = declaredMethod(delegate.getClass(), "unlockInfobaseState", InfobaseReference.class);
            lock = declaredField(delegate.getClass(), "lock").get(delegate);
            if (lock == null) return restoreFailed("EDT не отдала замок состояния синхронизации");
            Object storeProject = calculateStoreProject.invoke(delegate, project);
            initLockStates.invoke(delegate, storeProject, infobase);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return restoreFailed(failure(e).reason());
        }
        try {
            lockState.invoke(delegate, infobase);
        } catch (InvocationTargetException e) {
            return restoreFailed("не удалось взять замок состояния синхронизации базы: " + McpJobs.describe(cause(e)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return restoreFailed(failure(e).reason());
        }
        SyncV2.DumpInfoSettlement result = null;
        try {
            synchronized (lock) {
                if (Files.exists(moved.original())) {
                    Files.deleteIfExists(moved.aside());
                    result = new SyncV2.DumpInfoSettlement(SyncV2.DumpInfoFate.KEPT_EDT_COPY, null);
                } else {
                    try {
                        Files.move(moved.aside(), moved.original());
                        result = new SyncV2.DumpInfoSettlement(SyncV2.DumpInfoFate.RESTORED, null);
                    } catch (FileAlreadyExistsException e) {
                        // EDT успела записать свой между проверкой и переносом — её и оставляем.
                        Files.deleteIfExists(moved.aside());
                        result = new SyncV2.DumpInfoSettlement(SyncV2.DumpInfoFate.KEPT_EDT_COPY, null);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            result = restoreFailed("отложенный ConfigDumpInfo не вернуть на место: " + McpJobs.describe(e));
        } finally {
            String unlockFailure = unlock(unlockState, delegate, infobase);
            if (unlockFailure != null && result != null) {
                result = new SyncV2.DumpInfoSettlement(result.fate(),
                    result.reason() != null ? result.reason() + "; " + unlockFailure : unlockFailure);
            }
        }
        return result;
    }

    private static SyncV2.DumpInfoSettlement restoreFailed(String reason) {
        return new SyncV2.DumpInfoSettlement(SyncV2.DumpInfoFate.RESTORE_FAILED, reason);
    }

    /** Причина отказа, когда EDT хранит состояние проекта у другого проекта, а сам он не расширение (R7-3). */
    private static String storedElsewhere(String projectName, Object storeProject) {
        String owner = storeProject instanceof IProject p ? p.getName() : String.valueOf(storeProject);
        return "проект '" + projectName + "' — не проект конфигурации и не проект расширения: EDT хранит его "
            + "состояние синхронизации у проекта '" + owner + "', и сброс задел бы состояние того проекта";
    }

    /** @return причина, если снять замок не удалось, иначе {@code null} */
    private static String unlock(Method unlockState, Object delegate, InfobaseReference infobase) {
        try {
            unlockState.invoke(delegate, infobase);
            return null;
        } catch (InvocationTargetException e) {
            return "не удалось снять замок состояния синхронизации базы: " + McpJobs.describe(cause(e));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "не удалось снять замок состояния синхронизации базы: " + McpJobs.describe(e);
        }
    }

    private static boolean notBlank(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    /** Метод, объявленный в классе или его предках (внутренние методы делегата — {@code private}). */
    private static Method declaredMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, parameters);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // ищем выше
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    /** Поле, объявленное в классе или его предках (класс держателя у EDT — пакетный). */
    private static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // ищем выше
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Object staticFieldOrNull(Class<?> type, String name) {
        try {
            Field f = type.getField(name);
            return Modifier.isStatic(f.getModifiers()) ? f.get(null) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Throwable cause(InvocationTargetException e) {
        return e.getCause() != null ? e.getCause() : e;
    }

    private static SyncV2.Recheck failure(Throwable e) {
        if (e instanceof InvocationTargetException ite) return SyncV2.Recheck.failed(McpJobs.describe(cause(ite)));
        if (e instanceof NoSuchMethodException) {
            return SyncV2.Recheck.failed("в этой версии EDT нет внутреннего метода " + e.getMessage());
        }
        if (e instanceof NoSuchFieldException) {
            return SyncV2.Recheck.failed("в этой версии EDT нет внутреннего поля " + e.getMessage());
        }
        if (e instanceof CoreException) {
            return SyncV2.Recheck.failed("не удалось определить вид проекта: " + McpJobs.describe(e));
        }
        return SyncV2.Recheck.failed("внутренний API синхронизации EDT недоступен: " + McpJobs.describe(e));
    }
}
