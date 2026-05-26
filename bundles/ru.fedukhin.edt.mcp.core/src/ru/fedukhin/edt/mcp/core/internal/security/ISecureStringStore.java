package ru.fedukhin.edt.mcp.core.internal.security;

public interface ISecureStringStore {
    String get(String key);
    void put(String key, String value);
}
