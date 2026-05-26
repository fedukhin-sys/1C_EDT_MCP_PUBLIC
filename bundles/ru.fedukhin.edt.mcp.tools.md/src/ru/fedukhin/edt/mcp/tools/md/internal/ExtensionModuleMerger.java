package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges new annotated procedure/function source into an existing BSL module.
 *
 * <p>Cases:
 * <ul>
 *   <li>Module не содержит процедуру/функцию с тем же именем — append с blank-line
 *       separator (как было до v1.10.5).</li>
 *   <li>Module уже содержит {@code Процедура&nbsp;X(...)} или {@code Функция&nbsp;X(...)}
 *       с тем же именем — инжектируем тело новой процедуры в конец существующей
 *       (перед {@code КонецПроцедуры}/{@code КонецФункции}) с разделителем-комментарием.</li>
 * </ul>
 *
 * <p>Why: при двух последовательных {@code add_extension_method_override} с одной
 * базовой процедурой (например, оба {@code &После("ПриЗаписи")}) сейчас создаются
 * две процедуры с одинаковым именем {@code Расш1_ПриЗаписи_После}, и EDT-компилятор
 * валится «Метод уже определён». Workaround — ручной merge модуля.
 */
public final class ExtensionModuleMerger {

    private ExtensionModuleMerger() { }

    /** Сценарий merge. */
    public enum Action { APPENDED, MERGED }

    /** Результат: финальный текст модуля и какое действие было применено. */
    public record Result(String text, Action action, String procName) { }

    private static final Pattern PROC_OR_FUNC_HEADER = Pattern.compile(
            "(?mu)^[ \\t]*(?:Процедура|Функция|Procedure|Function)\\s+(\\S+?)\\s*\\(");

    /**
     * Сливает {@code newSource} в {@code existing}. Если {@code existing} пуст —
     * просто возвращает {@code newSource}.
     */
    public static Result merge(String existing, String newSource) {
        String trimmedExisting = existing == null ? "" : existing.stripTrailing();
        if (newSource == null || newSource.isEmpty()) {
            return new Result(trimmedExisting, Action.APPENDED, null);
        }
        String procName = extractProcName(newSource);
        if (procName == null) {
            return new Result(appendWith(trimmedExisting, newSource), Action.APPENDED, null);
        }
        ProcSpan span = findProcSpan(trimmedExisting, procName);
        if (span == null) {
            return new Result(appendWith(trimmedExisting, newSource), Action.APPENDED, procName);
        }
        String body = extractProcBody(newSource);
        if (body == null || body.isBlank()) {
            // вырожденный случай — нет тела для merge; падать обратно на append
            return new Result(appendWith(trimmedExisting, newSource), Action.APPENDED, procName);
        }
        String merged = injectBody(trimmedExisting, span, body);
        return new Result(merged, Action.MERGED, procName);
    }

    private static String appendWith(String existing, String newSource) {
        StringBuilder sb = new StringBuilder(existing);
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(newSource);
        if (!newSource.endsWith("\n")) sb.append('\n');
        return sb.toString();
    }

    /** Извлекает имя первой процедуры/функции из source ({@code null} если нет). */
    static String extractProcName(String source) {
        Matcher m = PROC_OR_FUNC_HEADER.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    private record ProcSpan(int endMarkerLineStart, String endMarker) { }

    /**
     * Ищет в {@code text} процедуру/функцию с именем {@code procName} и возвращает
     * позицию начала строки {@code КонецПроцедуры}/{@code КонецФункции}.
     * {@code null} если такой процедуры нет.
     *
     * <p>Замечание: поиск линейный — берём первое совпадение по имени и первый
     * следующий за ним end-маркер. Не поддерживает вложенные процедуры (в BSL
     * вложение запрещено, так что норма).
     */
    static ProcSpan findProcSpan(String text, String procName) {
        Pattern header = Pattern.compile(
                "(?mu)^[ \\t]*(Процедура|Функция|Procedure|Function)\\s+"
                        + Pattern.quote(procName) + "\\s*\\(");
        Matcher m = header.matcher(text);
        if (!m.find()) return null;
        boolean isFunc = m.group(1).equalsIgnoreCase("Функция") || m.group(1).equalsIgnoreCase("Function");
        String endMarker = isFunc ? "КонецФункции" : "КонецПроцедуры";
        Pattern endPat = Pattern.compile(
                "(?mu)^[ \\t]*(" + endMarker + "|EndProcedure|EndFunction)\\b");
        Matcher me = endPat.matcher(text);
        if (!me.find(m.end())) return null;
        return new ProcSpan(me.start(), endMarker);
    }

    /**
     * Извлекает тело первой процедуры/функции из {@code source}: всё между
     * первой строкой после header и строкой {@code КонецПроцедуры}/{@code КонецФункции}.
     */
    static String extractProcBody(String source) {
        Matcher m = PROC_OR_FUNC_HEADER.matcher(source);
        if (!m.find()) return null;
        // Конец header'а — конец строки (или конец `Экспорт`-suffix'а).
        int headerEnd = source.indexOf('\n', m.end());
        if (headerEnd < 0) return null;
        Pattern endPat = Pattern.compile(
                "(?mu)^[ \\t]*(КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)\\b");
        Matcher me = endPat.matcher(source);
        if (!me.find(headerEnd + 1)) return null;
        // Тело — от первой строки после header'а до начала end-marker строки.
        String body = source.substring(headerEnd + 1, me.start());
        return body;
    }

    private static String injectBody(String existing, ProcSpan span, String newBody) {
        // newBody может иметь leading/trailing whitespace — нормализуем
        String trimmed = newBody.replaceAll("(?m)^\\s+$", "").stripTrailing();
        StringBuilder sb = new StringBuilder(existing.length() + trimmed.length() + 128);
        sb.append(existing, 0, span.endMarkerLineStart);
        sb.append('\n');
        sb.append("\t// --- merged from MCP add_extension_method_override ---\n");
        sb.append(trimmed);
        if (!trimmed.endsWith("\n")) sb.append('\n');
        sb.append(existing.substring(span.endMarkerLineStart));
        // нормализация финального newline
        String result = sb.toString();
        if (!result.endsWith("\n")) result += "\n";
        return result;
    }
}
