package ru.fedukhin.edt.mcp.tools.quality.internal;

/**
 * Снимок одного {@code com._1c.g5.v8.dt.validation.marker.Marker},
 * созданного движком проверок. Неизменяемый.
 *
 * <p>Spike 2: EDT-маркеры НЕ являются Eclipse {@code IMarker} — они хранятся
 * в проприетарном BM-хранилище, доступном через {@code IMarkerManagerV2}.
 * Поле {@code markerId} содержит строковый идентификатор маркера
 * (a не {@code long} как у Eclipse {@code IMarker.getId()}).
 * Поле {@code checkId} содержит "short uid" маркера — результат
 * {@code ICheckRepository.getShortUid(CheckUid, IDtProject)}.
 */
public record CheckMarker(
        String markerId,    // строковый uuid/id EDT-маркера (НЕ Eclipse IMarker.getId(): long)
        String project,
        String path,
        int line,           // 0 если атрибут строки отсутствует
        String severity,    // "error" | "warning" | "info"
        String checkId,     // значение Marker.getCheckId() — short uid (project-scoped)
        String source,      // "v8codestyle" | "dt.check" | "edt" | "other"
        String message,
        String issueType) { // опционально — null если недоступно
}
