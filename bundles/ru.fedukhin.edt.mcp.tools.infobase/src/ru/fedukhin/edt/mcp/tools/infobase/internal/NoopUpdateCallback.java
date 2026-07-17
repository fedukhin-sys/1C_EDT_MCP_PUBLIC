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
 *   <li>{@code resolveInfobaseChanges} (core 21+) и {@code onInfobaseChanges} (core ≤19) →
 *       {@link InfobaseConflictResolutionResult#IGNORED}: за пользователя конфликты не решаем —
 *       реальные расхождения должен поднять сам конвейер синхронизации EDT (исключением или
 *       ERROR-статусом).</li>
 * </ul>
 *
 * <p>Различается не только имя метода, но и тип результата: до core 21 включительно это enum
 * {@link InfobaseConflictResolutionResult}, начиная с core 22 (EDT 2026.x) — класс-обёртка
 * {@code InfobaseConflictResolution}. Поэтому результат строится по фактическому
 * {@code method.getReturnType()} — см. {@link #conflictResolutionFor}.
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
                    case "onInfobaseChanges":       // core ≤19: enum
                    case "resolveInfobaseChanges":  // core 21: enum; core 22+ (EDT 2026.x): wrapper
                        return conflictResolutionFor(method.getReturnType());
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

    /**
     * Строит результат разрешения конфликтов под тот контракт, который объявлен в текущем рантайме.
     *
     * <p>Compile-time ссылка остаётся только на {@link InfobaseConflictResolutionResult} — этот enum
     * есть во всех поддерживаемых версиях (core 18–23). Wrapper-класс {@code InfobaseConflictResolution}
     * появился только в core 22, поэтому создаётся рефлексией по фактическому типу возврата.
     *
     * @param returnType фактический тип возврата метода интерфейса в текущем рантайме
     * @return либо сам enum {@code IGNORED} (core ≤21), либо обёртка над ним (core 22+)
     */
    private static Object conflictResolutionFor(Class<?> returnType) throws ReflectiveOperationException {
        if (returnType.isInstance(InfobaseConflictResolutionResult.IGNORED)) {
            return InfobaseConflictResolutionResult.IGNORED;
        }
        // core 22+: public InfobaseConflictResolution(InfobaseConflictResolutionResult)
        return returnType.getConstructor(InfobaseConflictResolutionResult.class)
            .newInstance(InfobaseConflictResolutionResult.IGNORED);
    }
}
