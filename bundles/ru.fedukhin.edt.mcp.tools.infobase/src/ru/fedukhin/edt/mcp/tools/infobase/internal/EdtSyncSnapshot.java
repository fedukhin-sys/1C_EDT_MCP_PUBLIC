package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.core.resources.IProject;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;

/**
 * Снимок состояния синхронизации пары «проект конфигурации — база» глазами 1C:EDT, ТОЛЬКО чтение, — для
 * {@code get_infobase_sync_state details=true}: записанные подписи файлов проекта конфигурации и его проектов
 * расширений в памяти EDT (с ними сравнивает {@code isProjectDirty}, а значит и {@code getEqualityState}) и на диске
 * ({@code index.idx} с {@code ConfigDumpInfo} — их EDT загрузит после перезапуска).
 *
 * <p><b>Как.</b> Внутренний делегат синхронизации v2 рефлексией — путь {@code isProjectDirty} (по байткоду EDT
 * 2026.1): {@code calculateStoreProject} → {@code findProjectInfobaseSynchronizationStateHolder} — только поиск, НЕ
 * {@code findOrCreate…}: созданный держатель изменил бы ответ {@code isProjectDirty}, и диагностика повлияла бы на
 * исход. Поле {@code state} держателя — {@code InfobaseSyncState} ({@code getEdtResourceMetadata},
 * {@code getExtensionSyncStates} с ключом «имя проекта расширения», {@code getTimestamp}; общий {@code UNDEFINED} —
 * «состояния нет»). Карты EDT меняет на месте под монитором {@code lock} делегата — копия снимается под ним же, под
 * монитором вызываются только геттеры. Диск — {@code synchronizationStore.readState()} держателя (у хранилища свой
 * замок чтения), вне монитора. Подписи — {@code getSignature()} значений рефлексией.
 *
 * <p>Классы EDT внутренние: статических ссылок на них нет, в сигнатурах — только JDK и публичный API. Класс в Guice
 * не биндится. Сбой — не исключение, а причина в {@link Read}.
 */
public final class EdtSyncSnapshot {

    private EdtSyncSnapshot() {}

    /** Записанное состояние одного проекта: путь → подпись ({@code null} — подписи нет), отметка времени (мс; 0 — нет). */
    public record Recorded(Map<String, byte[]> files, long timestamp) {}

    /**
     * Снимок пары: {@code memory} и {@code disk} — по имени проекта (конфигурации и расширений); проекта в карте нет —
     * для него состояние не записано. {@code memory == null} — снимка в памяти нет или его не прочитать, причина в
     * {@code failure}; {@code disk == null} — снимок на диске не прочитан, причина в {@code diskFailure}
     * ({@code null} — диск не запрашивался).
     */
    public record Read(Map<String, Recorded> memory, String failure, Map<String, Recorded> disk, String diskFailure) {}

    public static Read read(Object delegate, IProject configuration, InfobaseReference infobase,
                            Collection<String> extensionProjects, boolean withDisk) {
        Object holder;
        Object lock;
        try {
            Method calculateStoreProject = declaredMethod(delegate.getClass(), "calculateStoreProject", IProject.class);
            Method findHolder = declaredMethod(delegate.getClass(), "findProjectInfobaseSynchronizationStateHolder",
                IProject.class, InfobaseReference.class);
            lock = declaredField(delegate.getClass(), "lock").get(delegate);
            Object storeProject = calculateStoreProject.invoke(delegate, configuration);
            Object found = findHolder.invoke(delegate, storeProject, infobase);
            holder = found instanceof Optional<?> optional ? optional.orElse(null) : found;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            String reason = failure(e);
            return new Read(null, reason, null, withDisk ? reason : null);
        }
        if (holder == null) {
            return new Read(null, "в памяти EDT нет состояния синхронизации проекта " + configuration.getName()
                + " с этой базой — для EDT он и его расширения не совпадают с базой", null,
                withDisk ? "не читался: хранилище снимка доступно только через состояние в памяти" : null);
        }
        if (lock == null) {
            return new Read(null, "EDT не отдала замок состояния синхронизации", null, withDisk ? "не читался" : null);
        }
        Map<String, Recorded> memory = null;
        String failure = null;
        Object store = null;
        try {
            Field state = declaredField(holder.getClass(), "state");
            store = declaredField(holder.getClass(), "synchronizationStore").get(holder);
            Map<String, Raw> copied;
            synchronized (lock) {
                copied = capture(state.get(holder), configuration.getName(), extensionProjects);
            }
            memory = recorded(copied);
            if (memory == null) failure = "в памяти EDT состояние синхронизации пары не определено (UNDEFINED)";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failure = failure(e);
        }
        if (!withDisk) return new Read(memory, failure, null, null);
        Map<String, Recorded> disk = null;
        String diskFailure = null;
        if (store == null) {
            diskFailure = "у состояния синхронизации EDT нет хранилища";
        } else {
            try {
                disk = recorded(capture(store.getClass().getMethod("readState").invoke(store),
                    configuration.getName(), extensionProjects));
                if (disk == null) diskFailure = "на диске снимка нет";
            } catch (InvocationTargetException e) {
                diskFailure = "EDT не прочитала снимок с диска: " + McpJobs.describe(cause(e));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                diskFailure = failure(e);
            }
        }
        return new Read(memory, failure, disk, diskFailure);
    }

    /**
     * Путь → подпись из карты путь → {@code EdtResourceMetadata}: подпись — {@code getSignature()} рефлексией (класс
     * значения бандл не называет). Ключи-не-строки пропускаются. Значение {@code null} (объекта метаданных нет)
     * сводится к пустой подписи — EDT такую запись снимка считает расхождением всегда, но сама пишет вместо неё
     * объект с пустой подписью ({@code new EdtResourceMetadata(null, uuid)}).
     */
    public static Map<String, byte[]> signatures(Map<?, ?> metadata) throws ReflectiveOperationException {
        Map<String, byte[]> out = new HashMap<>(Math.max(16, metadata.size() * 2));
        Class<?> owner = null;
        Method getSignature = null;
        for (Map.Entry<?, ?> entry : metadata.entrySet()) {
            if (!(entry.getKey() instanceof String path)) continue;
            Object value = entry.getValue();
            byte[] signature = null;
            if (value != null) {
                if (value.getClass() != owner) {
                    owner = value.getClass();
                    getSignature = owner.getMethod("getSignature");
                }
                Object raw = getSignature.invoke(value);
                signature = raw instanceof byte[] bytes ? bytes : null;
            }
            out.put(path, signature);
        }
        return out;
    }

    /** Копия записанного состояния одного проекта: карта путь → {@code EdtResourceMetadata} и отметка времени. */
    private record Raw(Map<?, ?> metadata, long timestamp) {}

    /**
     * Копии карт {@code InfobaseSyncState} конфигурации и расширений ({@code getEdtResourceMetadata},
     * {@code getTimestamp}, {@code getExtensionSyncStates} с ключом «имя проекта расширения»); {@code null} —
     * состояния нет ({@code null} или общий {@code UNDEFINED}). Для снимка в памяти зовётся под монитором делегата:
     * только геттеры и копирование, подписи разбирает уже {@link #recorded} — {@code EdtResourceMetadata} неизменяем.
     */
    private static Map<String, Raw> capture(Object state, String configuration, Collection<String> extensionProjects)
            throws ReflectiveOperationException {
        if (state == null || isUndefined(state)) return null;
        Map<String, Raw> out = new LinkedHashMap<>();
        out.put(configuration, raw(state));
        Object extensions = state.getClass().getMethod("getExtensionSyncStates").invoke(state);
        if (extensions instanceof Map<?, ?> byProject) {
            for (String name : extensionProjects) {
                Object own = byProject.get(name);
                if (own != null && !isUndefined(own)) out.put(name, raw(own));
            }
        }
        return out;
    }

    private static Raw raw(Object state) throws ReflectiveOperationException {
        Object metadata = state.getClass().getMethod("getEdtResourceMetadata").invoke(state);
        Object timestamp = state.getClass().getMethod("getTimestamp").invoke(state);
        return new Raw(metadata instanceof Map<?, ?> map ? new HashMap<>(map) : Map.of(),
            timestamp instanceof Number number ? number.longValue() : 0L);
    }

    private static Map<String, Recorded> recorded(Map<String, Raw> copied) throws ReflectiveOperationException {
        if (copied == null) return null;
        Map<String, Recorded> out = new LinkedHashMap<>();
        for (Map.Entry<String, Raw> entry : copied.entrySet()) {
            out.put(entry.getKey(), new Recorded(signatures(entry.getValue().metadata()), entry.getValue().timestamp()));
        }
        return out;
    }

    private static boolean isUndefined(Object state) {
        try {
            Field undefined = state.getClass().getField("UNDEFINED");
            return Modifier.isStatic(undefined.getModifiers()) && undefined.get(null) == state;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
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

    private static Throwable cause(InvocationTargetException e) {
        return e.getCause() != null ? e.getCause() : e;
    }

    private static String failure(Throwable e) {
        if (e instanceof InvocationTargetException ite) return McpJobs.describe(cause(ite));
        if (e instanceof NoSuchMethodException) return "в этой версии EDT нет внутреннего метода " + e.getMessage();
        if (e instanceof NoSuchFieldException) return "в этой версии EDT нет внутреннего поля " + e.getMessage();
        return "внутренний API синхронизации EDT недоступен: " + McpJobs.describe(e);
    }
}
