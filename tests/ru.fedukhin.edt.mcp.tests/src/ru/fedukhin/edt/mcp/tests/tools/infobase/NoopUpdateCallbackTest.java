package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolutionResult;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.infobase.internal.NoopUpdateCallback;

/**
 * Проверяет dual-version поведение {@code InvocationHandler} из {@link NoopUpdateCallback}.
 *
 * <p>Сигнатура callback-метода разрешения конфликтов менялась между версиями 1C:EDT:
 *
 * <ul>
 *   <li>core 18/19 (EDT ≤2025.1) — {@code onInfobaseChanges}, возвращает <em>enum</em>
 *       {@link InfobaseConflictResolutionResult};</li>
 *   <li>core 21 — {@code resolveInfobaseChanges}, всё ещё возвращает enum;</li>
 *   <li>core 22/23 (EDT 2026.x) — {@code resolveInfobaseChanges}, возвращает <em>класс-обёртку</em>
 *       {@code InfobaseConflictResolution} с конструктором от enum.</li>
 * </ul>
 *
 * <p>Компилируемся против одной версии платформы, поэтому оба «чужих» контракта моделируются
 * локальными фикстурами, а хэндлер берётся у настоящего прокси через
 * {@link Proxy#getInvocationHandler}. Так один тест проверяет весь диапазон версий.
 */
public class NoopUpdateCallbackTest {

    /** Фикстура wrapper-класса core 22+ ({@code InfobaseConflictResolution}). */
    public static final class FakeInfobaseConflictResolution {

        private final InfobaseConflictResolutionResult result;

        public FakeInfobaseConflictResolution(InfobaseConflictResolutionResult result) {
            this.result = result;
        }

        public InfobaseConflictResolutionResult getResolutionResult() {
            return result;
        }
    }

    /** core ≤19: разрешение конфликтов зовётся {@code onInfobaseChanges} и отдаёт enum. */
    public interface Core18Api {
        InfobaseConflictResolutionResult onInfobaseChanges();
    }

    /** core 21: метод уже переименован, но возвращает всё ещё enum. */
    public interface Core21Api {
        InfobaseConflictResolutionResult resolveInfobaseChanges();
    }

    /** core 22/23 (EDT 2026.x): тот же метод возвращает класс-обёртку. */
    public interface Core22Api {
        FakeInfobaseConflictResolution resolveInfobaseChanges();
    }

    /** Собирает прокси заданного контракта поверх хэндлера из {@link NoopUpdateCallback}. */
    private static Object proxyFor(Class<?> iface) {
        InvocationHandler handler = Proxy.getInvocationHandler(NoopUpdateCallback.create());
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler);
    }

    @Test
    public void core18_onInfobaseChanges_returnsIgnoredEnum() {
        Core18Api api = (Core18Api) proxyFor(Core18Api.class);

        assertSame(InfobaseConflictResolutionResult.IGNORED, api.onInfobaseChanges());
    }

    @Test
    public void core21_resolveInfobaseChanges_returnsIgnoredEnum() {
        Core21Api api = (Core21Api) proxyFor(Core21Api.class);

        assertSame(InfobaseConflictResolutionResult.IGNORED, api.resolveInfobaseChanges());
    }

    @Test
    public void core22_resolveInfobaseChanges_returnsWrapperNotEnum() {
        Core22Api api = (Core22Api) proxyFor(Core22Api.class);

        FakeInfobaseConflictResolution resolution = api.resolveInfobaseChanges();

        assertNotNull("на core 22+ хэндлер обязан вернуть wrapper, а не null", resolution);
        assertTrue(
            "вернулся не wrapper — на настоящем EDT 2026.x это ClassCastException при маршалинге Proxy",
            resolution instanceof FakeInfobaseConflictResolution);
        assertSame(InfobaseConflictResolutionResult.IGNORED, resolution.getResolutionResult());
    }

    @Test
    public void onConfirm_isAcceptedHeadless() {
        assertTrue(NoopUpdateCallback.create().onConfirm(null, null, null, null));
    }
}
