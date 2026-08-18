package ru.fedukhin.edt.mcp.core.internal.security;

public interface ISecureStringStore {
    String get(String key);
    void put(String key, String value);

    /**
     * Сбрасывает изменения на постоянное хранение.
     *
     * <p>Нужен потому, что Equinox кладёт {@code put} в дерево в памяти и пишет
     * файл только при остановке фреймворка. Пока несколько инстанций EDT работают
     * одновременно, отложенная запись означает, что сгенерированный секрет
     * невидим соседям и может быть перетёрт при их выходе.
     */
    void flush();
}
