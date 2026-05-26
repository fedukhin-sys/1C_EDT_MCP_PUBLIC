package ru.fedukhin.edt.mcp.core.internal.security;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

public class EquinoxSecureStringStore implements ISecureStringStore {

    private static final String NODE = "ru.fedukhin.edt.mcp.core";

    private final ISecurePreferences node;

    public EquinoxSecureStringStore() {
        this.node = SecurePreferencesFactory.getDefault().node(NODE);
    }

    @Override public String get(String key) {
        try { return node.get(key, null); }
        catch (StorageException e) { return null; }
    }

    @Override public void put(String key, String value) {
        try { node.put(key, value, true); }
        catch (StorageException e) { throw new IllegalStateException("Cannot persist secure value", e); }
    }
}
