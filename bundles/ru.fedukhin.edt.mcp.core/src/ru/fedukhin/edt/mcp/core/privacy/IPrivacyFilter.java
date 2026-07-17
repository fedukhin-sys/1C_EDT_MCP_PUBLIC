package ru.fedukhin.edt.mcp.core.privacy;

/** Обезличивает результат инструмента перед отправкой клиенту. */
public interface IPrivacyFilter {
    /**
     * Возвращает обезличенную копию (или исходный объект, если обезличивание не требуется).
     *
     * @param infobaseKey ключ инфобазы для проверки флага {@code containsRealPersonalData};
     *                    {@code null} → инфобаза неизвестна, fail-closed (обезличиваем всегда).
     *                    Вычисляется инструментом, а не берётся из сырых args.
     */
    Object redact(String toolName, String infobaseKey, Object result);
}
