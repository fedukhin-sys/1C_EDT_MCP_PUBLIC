package ru.fedukhin.edt.mcp.core.internal.security;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

public class EquinoxSecureStringStore implements ISecureStringStore {

    private static final String NODE = "ru.fedukhin.edt.mcp.core";

    private final ISecurePreferences node;

    public EquinoxSecureStringStore() {
        this(SecurePreferencesFactory.getDefault().node(NODE));
    }

    /** Test seam. */
    EquinoxSecureStringStore(ISecurePreferences node) {
        this.node = node;
    }

    /**
     * @return значение либо {@code null}, если его нет
     * @throws IllegalStateException хранилище недоступно (например, пользователь не ввёл
     *     мастер-пароль). Раньше сбой отдавался как {@code null}, неотличимо от «значения нет», —
     *     и вызывающий {@link SecureTokenStore} молча генерировал новое: bearer-токен ротировался
     *     (клиенты отваливались), а вместе с ним и HMAC-ключ приватности, из-за чего псевдонимы
     *     ПДн переставали совпадать с выданными раньше.
     */
    @Override public String get(String key) {
        try { return node.get(key, null); }
        catch (StorageException e) {
            throw new IllegalStateException("Cannot read secure value '" + key
                + "': secure storage unavailable", e);
        }
    }

    @Override public void put(String key, String value) {
        try { node.put(key, value, true); }
        catch (StorageException e) { throw new IllegalStateException("Cannot persist secure value", e); }
    }
}
