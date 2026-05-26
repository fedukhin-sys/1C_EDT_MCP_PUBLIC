package ru.fedukhin.edt.mcp.tools.quality.internal;

/**
 * Reference to a section of the «Стандарты разработки V8» document on its.1c.ru.
 * Emitted by {@code check_describe} for v8codestyle checks that ship an HTML description
 * file containing a {@code <a href="https://its.1c.ru/db/v8std...">} hyperlink (Spike 5).
 *
 * @param document constant {@code "Стандарты разработки V8"}
 * @param section  numeric section id (e.g. {@code "455"})
 * @param anchor   optional sub-anchor (e.g. {@code "3.6"}); {@code null} if absent
 */
public record StandardRef(String document, String section, String anchor) {

    public static final String DOCUMENT_TITLE = "Стандарты разработки V8";
}
