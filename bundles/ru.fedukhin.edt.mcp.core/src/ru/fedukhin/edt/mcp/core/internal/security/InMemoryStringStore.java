package ru.fedukhin.edt.mcp.core.internal.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Non-persistent string store. Only used in headless test runtimes where
 * Equinox secure storage can't initialize a password provider (no UI bundle,
 * no DPAPI). Selected via the system property {@code mcp.security.useInMemory}.
 */
public class InMemoryStringStore implements ISecureStringStore {

    private final ConcurrentMap<String, String> values = new ConcurrentHashMap<>();

    @Override public String get(String key) { return values.get(key); }

    @Override public void put(String key, String value) { values.put(key, value); }

    /** Нечего сбрасывать: хранилище и так живёт только в памяти процесса. */
    @Override public void flush() { }
}
