package ru.fedukhin.edt.mcp.tools.quality.internal;

/**
 * Снимок одной записи из {@code ICheckRepository}. Неизменяемый.
 *
 * <p>Поле {@code category} — заголовок непосредственного родительского узла
 * {@code ICheckCategory} (из {@code INamedElement.getTitle()}); {@code null}, если
 * родительская категория не определена.
 */
public record CheckEntry(
        String checkId,
        String title,
        String description,   // markdown / plain; null в кратком режиме
        String severity,      // "error" | "warning" | "info"
        String source,        // "v8codestyle" | "dt.check" | "edt" | "other"
        String category,      // заголовок непосредственной ICheckCategory; null если не задана
        boolean enabled) {

    /**
     * Краткая форма, используемая {@code check_catalog} (без описания).
     */
    public CheckEntry brief() {
        return new CheckEntry(checkId, title, null, severity, source, category, enabled);
    }
}
