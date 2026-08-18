package ru.fedukhin.edt.mcp.core.internal.security;

import java.security.SecureRandom;
import java.util.Base64;

public class SecureTokenStore {

    static final String KEY = "mcp.bearer.token";
    static final String PRIVACY_KEY = "mcp.privacy.hmac.key";

    private final ISecureStringStore store;
    private final SecureRandom random = new SecureRandom();

    public SecureTokenStore(ISecureStringStore store) {
        this.store = store;
    }

    private static final java.time.Duration LOCK_WAIT = java.time.Duration.ofSeconds(10);

    public String getOrGenerate() {
        return getOrCreate(KEY);
    }

    public synchronized String regenerate() {
        return withLock(() -> {
            String token = randomToken();
            store.put(KEY, token);
            store.flush();
            return token;
        });
    }

    public String getOrGeneratePrivacyKey() {
        return getOrCreate(PRIVACY_KEY);
    }

    /**
     * Хранилище Equinox — одно на пользователя, а инстанций EDT несколько, поэтому
     * {@code synchronized} тут недостаточно: две инстанции, стартовавшие при пустом
     * хранилище, сгенерировали бы РАЗНЫЕ значения. Для HMAC-ключа обезличивания это
     * означает расхождение псевдонимов одного и того же субъекта, необратимое
     * постфактум — обратной таблицы нет.
     *
     * <p>Отсюда схема «замок → перечитать → создать, если нет → записать → сбросить»:
     * перечитывание под замком обязательно, потому что пока мы ждали, значение мог
     * записать сосед.
     */
    private String getOrCreate(String key) {
        String existing = store.get(key);
        if (existing != null && !existing.isEmpty()) return existing;
        return withLock(() -> {
            String again = store.get(key);
            if (again != null && !again.isEmpty()) return again;
            String value = randomToken();
            store.put(key, value);
            store.flush();
            return value;
        });
    }

    private String randomToken() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * Замок — средство согласования, а не условие работы: если взять его не вышло,
     * выполняем то же самое без него. Иначе недоступность каталога состояния
     * оставила бы сервер вовсе без bearer-токена.
     */
    private String withLock(java.util.function.Supplier<String> body) {
        String holder = "secure-store pid=" + ProcessHandle.current().pid();
        try (ru.fedukhin.edt.mcp.core.ipc.InterProcessLock l =
                 ru.fedukhin.edt.mcp.core.ipc.InterProcessLock.acquire("secure-store", holder, LOCK_WAIT)) {
            return body.get();
        } catch (ru.fedukhin.edt.mcp.core.ipc.LockTimeoutException | java.io.IOException e) {
            return body.get();
        }
    }
}
