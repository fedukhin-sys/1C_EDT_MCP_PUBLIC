package ru.fedukhin.edt.mcp.tools.infobase.internal;

/**
 * Состояние синхронизации одного проекта с базой глазами 1C:EDT — входы правила запуска клиента
 * ({@link UpdateStateRule}). Значение, которое получить не удалось (нет метода в этой версии EDT, сбой), —
 * {@code null}, причина — в {@code error}.
 *
 * @param kind {@link #CONFIGURATION} или {@link #EXTENSION}
 * @param equalityState имя константы {@code InfobaseEqualityState}: {@code EQUAL}, {@code NOT_EQUAL}, {@code LOADING}
 * @param connected {@code IInfobaseSynchronizationManager.isConnected}
 * @param projectDirty {@code IInfobaseSynchronizationStateManager.isProjectDirty} (API v2, 1C:EDT 2026.1+)
 * @param error почему часть значений {@code null}; {@code null} — получено всё
 */
public record ProjectSyncState(String project, String kind, String equalityState, Boolean connected,
                               Boolean projectDirty, String error) {

    public static final String CONFIGURATION = "configuration";
    public static final String EXTENSION = "extension";
}
