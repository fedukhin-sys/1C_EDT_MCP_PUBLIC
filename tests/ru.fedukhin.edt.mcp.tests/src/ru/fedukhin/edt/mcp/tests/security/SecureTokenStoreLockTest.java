package ru.fedukhin.edt.mcp.tests.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.internal.security.ISecureStringStore;
import ru.fedukhin.edt.mcp.core.internal.security.SecureTokenStore;
import ru.fedukhin.edt.mcp.core.ipc.McpHome;

/**
 * Секреты общие для всех инстанций EDT одного пользователя. Расхождение
 * HMAC-ключа обезличивания необратимо: обратной таблицы псевдонимов нет,
 * поэтому запись обязана попадать на диск сразу, а не при остановке фреймворка.
 */
public class SecureTokenStoreLockTest {

    private Path home;
    private String saved;

    @Before
    public void up() throws Exception {
        saved = System.getProperty(McpHome.SYS_PROP);
        home = Files.createTempDirectory("edt-mcp-secure-");
        System.setProperty(McpHome.SYS_PROP, home.toString());
    }

    @After
    public void down() throws Exception {
        if (saved == null) System.clearProperty(McpHome.SYS_PROP);
        else System.setProperty(McpHome.SYS_PROP, saved);
        deleteTree(home);
    }

    /** Считает вызовы flush и хранит значения в обычной карте. */
    private static final class CountingStore implements ISecureStringStore {
        private final Map<String, String> data = new HashMap<>();
        private final AtomicInteger flushes = new AtomicInteger();

        @Override public String get(String key) { return data.get(key); }
        @Override public void put(String key, String value) { data.put(key, value); }
        @Override public void flush() { flushes.incrementAndGet(); }
    }

    @Test
    public void getOrGeneratePrivacyKey_flushesImmediately() {
        CountingStore store = new CountingStore();

        String first = new SecureTokenStore(store).getOrGeneratePrivacyKey();

        assertTrue("после генерации ключа обязан быть flush", store.flushes.get() >= 1);
        assertEquals("повторный вызов обязан вернуть тот же ключ",
                first, new SecureTokenStore(store).getOrGeneratePrivacyKey());
    }

    @Test
    public void getOrGenerate_flushesImmediately() {
        CountingStore store = new CountingStore();

        String token = new SecureTokenStore(store).getOrGenerate();

        assertTrue("после генерации токена обязан быть flush", store.flushes.get() >= 1);
        assertEquals(token, new SecureTokenStore(store).getOrGenerate());
    }

    /** Повторное чтение существующего значения не должно трогать диск. */
    @Test
    public void getOrGenerate_onExistingValue_doesNotFlush() {
        CountingStore store = new CountingStore();
        new SecureTokenStore(store).getOrGenerate();
        int after = store.flushes.get();

        new SecureTokenStore(store).getOrGenerate();

        assertEquals("лишних записей на диск быть не должно", after, store.flushes.get());
    }

    @Test
    public void regenerate_replacesTokenAndFlushes() {
        CountingStore store = new CountingStore();
        SecureTokenStore tokens = new SecureTokenStore(store);
        String before = tokens.getOrGenerate();
        int flushesBefore = store.flushes.get();

        String after = tokens.regenerate();

        assertNotEquals("токен должен смениться", before, after);
        assertTrue("регенерация обязана сбросить на диск", store.flushes.get() > flushesBefore);
        assertEquals(after, tokens.getOrGenerate());
    }

    /** Токен и ключ приватности — разные секреты и не должны совпадать. */
    @Test
    public void tokenAndPrivacyKey_areDistinct() {
        CountingStore store = new CountingStore();
        SecureTokenStore tokens = new SecureTokenStore(store);

        assertNotEquals(tokens.getOrGenerate(), tokens.getOrGeneratePrivacyKey());
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException ignored) { /* best-effort */ }
            });
        }
    }
}
