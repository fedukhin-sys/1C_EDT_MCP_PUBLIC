package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolutionResult;
import java.lang.reflect.Proxy;

/**
 * Фабрика headless-реализации {@link IInfobaseUpdateCallback} для неинтерактивного deploy.
 *
 * <p>Раньше это был статический {@code implements IInfobaseUpdateCallback}. Но сигнатура
 * метода {@code resolveInfobaseChanges} менялась между версиями 1C:EDT — в EDT 2026.x
 * (dt.platform.services.core 23.0.0) в неё добавлен параметр {@code Set<String>}. Статическая
 * реализация ломает компиляцию/загрузку класса ровно на одной версии платформы.
 *
 * <p>Поэтому реализация создаётся через {@link Proxy}: динамический прокси реализует интерфейс
 * ровно так, как тот загружен в текущем рантайме (сколько бы аргументов ни было у методов),
 * а {@code InvocationHandler} диспетчеризует по <em>имени</em> метода. Один и тот же байткод
 * совместим и со старыми, и с новыми версиями EDT.
 *
 * <p>Поведение headless-режима:
 * <ul>
 *   <li>{@code onConfirm} → {@code true}: принять изменения структуры ИБ без вопросов;</li>
 *   <li>{@code resolveInfobaseChanges} → {@link InfobaseConflictResolutionResult#IGNORED}:
 *       за пользователя конфликты не решаем — реальные расхождения должен поднять
 *       сам конвейер синхронизации EDT (исключением или ERROR-статусом).</li>
 * </ul>
 */
public final class NoopUpdateCallback {

    private NoopUpdateCallback() {}

    /** Создаёт версионно-независимую headless-реализацию {@link IInfobaseUpdateCallback}. */
    public static IInfobaseUpdateCallback create() {
        return (IInfobaseUpdateCallback) Proxy.newProxyInstance(
            IInfobaseUpdateCallback.class.getClassLoader(),
            new Class<?>[] { IInfobaseUpdateCallback.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "onConfirm":
                        return Boolean.TRUE;
                    case "resolveInfobaseChanges":
                        return InfobaseConflictResolutionResult.IGNORED;
                    case "toString":
                        return "NoopUpdateCallback(proxy)";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    default:
                        // Разумные дефолты на случай новых методов интерфейса в будущих версиях EDT.
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class || rt == Boolean.class) {
                            return Boolean.FALSE;
                        }
                        return null;
                }
            });
    }
}
