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

    public synchronized String getOrGenerate() {
        String existing = store.get(KEY);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        return regenerate();
    }

    public synchronized String regenerate() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        store.put(KEY, token);
        return token;
    }

    public synchronized String getOrGeneratePrivacyKey() {
        String existing = store.get(PRIVACY_KEY);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        store.put(PRIVACY_KEY, key);
        return key;
    }
}
