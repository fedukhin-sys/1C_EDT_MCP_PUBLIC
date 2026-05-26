package ru.fedukhin.edt.mcp.tools.quality.internal;

import com.e1c.g5.v8.dt.check.settings.IssueSeverity;

/**
 * Транслирует EDT-перечисления серьёзности в трёхуровневую строку серьёзности MCP
 * ("error" / "warning" / "info").
 *
 * <p>Оба перечисления — {@link IssueSeverity} (из {@code com.e1c.g5.v8.dt.check.settings},
 * используется {@code ICheckDescription.getSeverity()}) и
 * {@code com._1c.g5.v8.dt.validation.marker.MarkerSeverity}
 * (используется {@code Marker.getSeverity()}) — имеют одинаковые имена значений,
 * поэтому канонический транслятор работает по строке {@code enum.name()}.
 */
public final class IssueSeverityName {

    private IssueSeverityName() {
    }

    /**
     * Транслирует {@link IssueSeverity} в строку серьёзности MCP.
     *
     * @param severity значение перечисления или {@code null}
     * @return "error", "warning" или "info"
     */
    public static String fromEdt(IssueSeverity severity) {
        return severity == null ? "info" : fromEdtName(severity.name());
    }

    /**
     * Транслирует имя значения EDT-перечисления серьёзности в строку серьёзности MCP.
     * Принимает имена как из {@link IssueSeverity}, так и из {@code MarkerSeverity}
     * (Spike 2: оба перечисления имеют одинаковые имена, плюс {@code NONE} и {@code ERRORS}
     * есть только в {@code MarkerSeverity}).
     *
     * @param name имя значения перечисления или {@code null}
     * @return "error", "warning" или "info"
     */
    public static String fromEdtName(String name) {
        if (name == null) return "info";
        return switch (name) {
            case "BLOCKER", "CRITICAL", "MAJOR" -> "error";
            case "MINOR"                        -> "warning";
            case "TRIVIAL", "NONE"              -> "info";
            default                             -> "info";
        };
    }
}
