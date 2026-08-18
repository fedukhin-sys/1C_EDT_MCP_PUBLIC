package ru.fedukhin.edt.mcp.tests.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.internal.security.ISecureStringStore;
import ru.fedukhin.edt.mcp.core.internal.security.SecureTokenStore;

public class SecureTokenStoreTest {

    static class InMemoryStore implements ISecureStringStore {
        final Map<String, String> map = new HashMap<>();
        @Override public String get(String k) { return map.get(k); }
        @Override public void put(String k, String v) { map.put(k, v); }
        /** Нечего сбрасывать: карта в памяти. */
        @Override public void flush() { }
    }

    @Test
    public void getOrGenerate_returnsSameTokenOnRepeatedCalls() {
        SecureTokenStore s = new SecureTokenStore(new InMemoryStore());
        String t1 = s.getOrGenerate();
        String t2 = s.getOrGenerate();
        assertEquals(t1, t2);
    }

    @Test
    public void getOrGenerate_returnsBase64UrlOfSufficientLength() {
        SecureTokenStore s = new SecureTokenStore(new InMemoryStore());
        String t = s.getOrGenerate();
        assertTrue("token too short: " + t.length(), t.length() >= 43);
        assertTrue("non-url-safe chars", t.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    public void regenerate_replacesToken() {
        SecureTokenStore s = new SecureTokenStore(new InMemoryStore());
        String t1 = s.getOrGenerate();
        String t2 = s.regenerate();
        assertNotEquals(t1, t2);
        assertEquals(t2, s.getOrGenerate());
    }

    @Test
    public void persistedToken_isPickedUpOnNextStore() {
        InMemoryStore store = new InMemoryStore();
        SecureTokenStore a = new SecureTokenStore(store);
        String t = a.getOrGenerate();
        SecureTokenStore b = new SecureTokenStore(store);
        assertEquals(t, b.getOrGenerate());
    }
}
