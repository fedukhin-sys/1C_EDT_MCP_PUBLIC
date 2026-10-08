package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.eclipse.core.resources.IProject;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;

/**
 * Итог «обновлять ли базу при запуске клиента» так, как его считает сама 1C:EDT: {@code IApplicationManager
 * .getUpdateState(приложение базы)} передаёт вопрос {@code InfobaseApplicationProvisionDelegate.getUpdateState} — ровно
 * то, что спрашивает {@code ApplicationUiSupport.ensureUpdated} при запуске (EDT 2026.1, по байткоду).
 *
 * <p><b>Без записи.</b> Приложение не берётся из {@code getApplications}/{@code findApplication…}: оба пути проходят
 * {@code initIfNeeded}, который назначает и сохраняет в настройках порт отладки приложения, если его ещё нет. Вместо
 * этого приложение собирается так же, как его собирает сама EDT: {@code new InfobaseApplication(имя базы, тип, база,
 * проект)} — публичный конструктор только запоминает поля; тип — элемент {@code getApplicationTypes()} с
 * {@link #INFOBASE_TYPE}. {@code getUpdateState} ничего не пишет: он спрашивает {@code getEqualityState} и
 * {@code isConnected} проекта и его расширений.
 *
 * <p><b>Только рефлексия.</b> Пакета {@code com.e1c.g5.dt.applications} может не быть или он другой на старой ветке
 * EDT — у бандла нет ни {@code Import-Package}, ни статических ссылок на него: методы ищутся по имени у публичных
 * интерфейсов ({@link #MANAGER}, {@code IApplicationType}) среди интерфейсов объектов, а нет таких — у их классов.
 * Класс {@link #INFOBASE_APPLICATION} — внутренний, его загружает вызывающий загрузчиком бандла
 * {@link #INFOBASES_BUNDLE}.
 *
 * <p>Класс в Guice не биндится. Сбой — не исключение, а {@code Result(null, причина)}.
 */
public final class EdtApplicationState {

    /** Публичный интерфейс сервиса приложений EDT. */
    public static final String MANAGER = "com.e1c.g5.dt.applications.IApplicationManager";
    /** Бандл приложений-баз EDT. */
    public static final String INFOBASES_BUNDLE = "com.e1c.g5.dt.applications.infobases";
    /** Внутренний класс приложения-базы (публичный конструктор {@code (String, IApplicationType, InfobaseReference, IProject)}). */
    public static final String INFOBASE_APPLICATION =
        "com.e1c.g5.dt.internal.applications.infobases.InfobaseApplication";
    /** Тип приложения-базы — {@code plugin.xml} бандла {@link #INFOBASES_BUNDLE}. */
    public static final String INFOBASE_TYPE = "com.e1c.g5.dt.applications.type.infobase";

    private static final String TYPE_API = "com.e1c.g5.dt.applications.IApplicationType";

    private EdtApplicationState() {}

    /** {@code state} — имя константы {@code ApplicationUpdateState}; иначе {@code error} — почему её нет. */
    public record Result(String state, String error) {}

    /**
     * @param manager сервис {@link #MANAGER}; {@code null} — недоступен
     * @param applicationClass класс {@link #INFOBASE_APPLICATION}; {@code null} — его нет в этой версии EDT
     */
    public static Result query(Object manager, Class<?> applicationClass, IProject configuration,
                               InfobaseReference infobase) {
        if (manager == null) return new Result(null, "сервис приложений EDT недоступен");
        if (applicationClass == null) {
            return new Result(null, "в этой версии EDT нет класса приложения базы " + INFOBASE_APPLICATION
                + " (бандл " + INFOBASES_BUNDLE + ")");
        }
        Class<?> owner = apiOf(manager, MANAGER);
        try {
            Object type = infobaseType(owner.getMethod("getApplicationTypes").invoke(manager));
            if (type == null) return new Result(null, "EDT не знает тип приложения " + INFOBASE_TYPE);
            Object application = constructor(applicationClass, type)
                .newInstance(infobase.getName(), type, infobase, configuration);
            Object state = methodByName(owner, "getUpdateState", 1).invoke(manager, application);
            if (state == null) return new Result(null, "EDT не отдала состояние приложения");
            return new Result(state instanceof Enum<?> constant ? constant.name() : state.toString(), null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new Result(null, "EDT не отдала состояние приложения: " + McpJobs.describe(cause));
        } catch (NoSuchMethodException e) {
            return new Result(null, "в этой версии EDT нет метода " + e.getMessage());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return new Result(null, "API приложений EDT недоступен: " + McpJobs.describe(e));
        }
    }

    /** Элемент {@code getApplicationTypes()} с {@code getId()} = {@link #INFOBASE_TYPE}. */
    private static Object infobaseType(Object types) throws ReflectiveOperationException {
        if (!(types instanceof Iterable<?> list)) return null;
        for (Object type : list) {
            if (type != null && INFOBASE_TYPE.equals(apiOf(type, TYPE_API).getMethod("getId").invoke(type))) {
                return type;
            }
        }
        return null;
    }

    /** Публичный конструктор {@code (String, тип, InfobaseReference, IProject)} — как у {@code InfobaseApplication}. */
    private static Constructor<?> constructor(Class<?> applicationClass, Object type) throws NoSuchMethodException {
        for (Constructor<?> candidate : applicationClass.getConstructors()) {
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 4 && p[0] == String.class && p[1].isInstance(type) && p[2] == InfobaseReference.class
                    && p[3] == IProject.class) {
                return candidate;
            }
        }
        throw new NoSuchMethodException(applicationClass.getName() + "(String, IApplicationType, InfobaseReference, "
            + "IProject)");
    }

    /** Публичный метод с таким именем и числом параметров (параметр — {@code IApplication}, класс бандлу не виден). */
    private static Method methodByName(Class<?> owner, String name, int parameters) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    /** Публичный интерфейс с таким именем среди интерфейсов объекта (классы реализаций у EDT внутренние), иначе класс. */
    private static Class<?> apiOf(Object target, String interfaceName) {
        Class<?> api = interfaceNamed(target.getClass(), interfaceName);
        return api != null ? api : target.getClass();
    }

    private static Class<?> interfaceNamed(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> candidate : c.getInterfaces()) {
                Class<?> hit = candidate.getName().equals(name) ? candidate : interfaceNamed(candidate, name);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
