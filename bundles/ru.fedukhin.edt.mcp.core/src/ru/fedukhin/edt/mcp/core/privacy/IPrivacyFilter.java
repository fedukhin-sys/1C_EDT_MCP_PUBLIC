package ru.fedukhin.edt.mcp.core.privacy;

import java.util.Map;

/** Обезличивает результат инструмента перед отправкой клиенту. */
public interface IPrivacyFilter {
    /** Возвращает обезличенную копию (или исходный объект, если обезличивание не требуется). */
    Object redact(String toolName, Map<String, Object> args, Object result);
}
