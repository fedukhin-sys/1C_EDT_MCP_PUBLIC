package ru.fedukhin.edt.mcp.tools.tests.internal;

import jakarta.inject.Singleton;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates.Language;

/**
 * Дописывает тестовый метод xUnitFor1C в текст модуля и регистрирует его в
 * {@code ИсполняемыеСценарии}/{@code ExecutableScenarios}.
 *
 * <p>Правила (регрессии 2026-10-08):
 * <ul>
 * <li>имя метода получает префикс {@code Тест_}/{@code Test_} ровно один раз; под тем же именем
 *     идут строка регистрации и {@link AppendResult#fqn} — иначе {@code run_test_method} по
 *     {@code fqn} из ответа падал «Метод объекта не обнаружен»;</li>
 * <li>метод всегда {@code Экспорт}/{@code Export}, {@code КонецПроцедуры} — на своей строке;</li>
 * <li>метод вставляется внутрь области {@code ПрограммныйИнтерфейс}/{@code Public} перед её
 *     {@code #КонецОбласти}; нет такой области — перед первым {@code #КонецОбласти} вне тел
 *     методов; нет областей — в конец модуля. До и после метода — пустая строка;</li>
 * <li>переводы строк — как в файле (CRLF или LF, по большинству), BOM сохраняется.</li>
 * </ul>
 */
@Singleton
public class BslTestMethodAppender {

    private static final String BOM = String.valueOf((char) 0xFEFF);

    private static final Pattern RU_END_PROC = Pattern.compile(
            "(?is)Процедура\\s+ИсполняемыеСценарии\\s*\\(.*?\\)\\s*Экспорт\\s*\\r?\\n(.*?)(КонецПроцедуры)");
    private static final Pattern EN_END_PROC = Pattern.compile(
            "(?is)Procedure\\s+ExecutableScenarios\\s*\\(.*?\\)\\s*Export\\s*\\r?\\n(.*?)(EndProcedure)");

    // Разбор областей — построчно, по началу строки. Регистр не важен (как в BSL);
    // UNICODE_CASE — иначе (?i) для кириллицы не работает.
    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern REGION_OPEN = Pattern.compile(
            "^\\s*#(?:Область|Region)(?:\\s+([\\p{L}\\p{N}_]+))?", CI);
    private static final Pattern REGION_CLOSE = Pattern.compile(
            "^\\s*#(?:КонецОбласти|EndRegion)(?![\\p{L}\\p{N}_])", CI);
    private static final Pattern METHOD_OPEN = Pattern.compile(
            "^\\s*(?:(?:Асинх|Async)\\s+)?(?:Процедура|Функция|Procedure|Function)\\s+[\\p{L}\\p{N}_]", CI);
    private static final Pattern METHOD_CLOSE = Pattern.compile(
            "^\\s*(?:КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)(?![\\p{L}\\p{N}_])", CI);

    public static final class AppendResult {
        public final String newText;
        public final boolean alreadyExisted;
        /**
         * Тест реально зарегистрирован в {@code ИсполняемыеСценарии}/{@code ExecutableScenarios}.
         * {@code false}, если в модуле нет этой процедуры (регистрировать негде) — тогда метод
         * в модуле есть, но раннер xUnitFor1C его не запустит.
         */
        public final boolean registered;
        /** Фактическое имя метода в модуле (с префиксом Тест_/Test_ ровно один раз). */
        public final String fqn;
        public AppendResult(String t, boolean e, boolean r, String fqn) {
            this.newText = t; this.alreadyExisted = e; this.registered = r; this.fqn = fqn;
        }
    }

    public AppendResult append(String moduleText, String methodName, Language lang, String userBody) {
        // BOM — не часть текста: с ним первая строка не распознаётся как директива области.
        boolean bom = moduleText.startsWith(BOM);
        String text = bom ? moduleText.substring(BOM.length()) : moduleText;
        String eol = detectEol(text);
        String fqn = XUnitTemplates.fqn(methodName, lang);

        if (methodExists(text, fqn)) {
            // Идемпотентный повтор: метод есть — но регистрация могла и отсутствовать
            // (например, метод дописан прошлым вызовом в модуль без ИсполняемыеСценарии).
            return new AppendResult(moduleText, true, isRegistered(text, fqn), fqn);
        }

        // Add ДобавитьТест/AddTest to ExecutableScenarios body — insert right before
        // КонецПроцедуры/EndProcedure (group(2)). We splice on absolute offsets
        // instead of String.replace(existing, …): when the body is empty (fresh
        // scaffold), String.replace("", X) would insert X between every character
        // of the surrounding match and corrupt the file.
        Pattern endProc = (lang == Language.RU) ? RU_END_PROC : EN_END_PROC;
        Matcher m = endProc.matcher(text);
        boolean registered = m.find();
        if (registered) {
            String registerLine = (lang == Language.RU)
                ? "\tЮнитТесты.ДобавитьТест(\"" + fqn + "\");" + eol
                : "\tUnitTests.AddTest(\"" + fqn + "\");" + eol;
            int insertAt = m.start(2);
            text = text.substring(0, insertAt) + registerLine + text.substring(insertAt);
        }

        String method = XUnitTemplates.methodBody(fqn, lang, userBody, eol);
        text = insertMethod(text, method, eol);
        return new AppendResult((bom ? BOM : "") + text, false, registered, fqn);
    }

    /** Есть ли в модуле процедура {@code fqn} (регистр имени не важен, как в BSL). */
    private static boolean methodExists(String moduleText, String fqn) {
        return Pattern.compile("(?imu)^\\s*(Процедура|Procedure)\\s+" + Pattern.quote(fqn) + "\\s*\\(")
                .matcher(moduleText).find();
    }

    /** Есть ли в модуле строка регистрации {@code ДобавитьТест("fqn")} / {@code AddTest("fqn")}. */
    private static boolean isRegistered(String moduleText, String fqn) {
        return Pattern.compile("(?iu)(ДобавитьТест|AddTest)\\s*\\(\\s*\"" + Pattern.quote(fqn) + "\"")
                .matcher(moduleText).find();
    }

    /** Перевод строки файла: CRLF или LF — по большинству строк; без строк — CRLF, как принято в 1С. */
    static String detectEol(String text) {
        int crlf = 0, lf = 0;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            if (i > 0 && text.charAt(i - 1) == '\r') crlf++; else lf++;
        }
        return lf > crlf ? "\n" : "\r\n";
    }

    /**
     * Вставляет текст метода ({@code method} заканчивается на {@code eol}) перед закрывающей
     * директивой выбранной области с пустой строкой до и после; без областей — в конец модуля
     * с пустой строкой перед методом.
     */
    private static String insertMethod(String text, String method, String eol) {
        String blank = eol + eol;
        int at = insertionOffset(text);
        if (at < 0) {
            StringBuilder sb = new StringBuilder(text);
            if (sb.length() > 0 && !text.endsWith(eol)) sb.append(eol);
            if (sb.length() > 0 && !sb.toString().endsWith(blank)) sb.append(eol);
            return sb.append(method).toString();
        }
        String before = text.substring(0, at);
        StringBuilder sb = new StringBuilder(before);
        if (!before.isEmpty() && !before.endsWith(blank)) sb.append(eol);
        return sb.append(method).append(eol).append(text, at, text.length()).toString();
    }

    /**
     * Начало строки с {@code #КонецОбласти}, перед которой вставлять метод: закрытие области
     * {@code ПрограммныйИнтерфейс}/{@code Public}, иначе первое закрытие области вне тел методов
     * (директивы внутри процедур пропускаются — иначе метод попал бы внутрь чужой процедуры).
     * {@code -1} — подходящих областей нет.
     */
    private static int insertionOffset(String text) {
        Deque<String> open = new ArrayDeque<>();
        boolean inMethod = false;
        int publicClose = -1;
        int firstClose = -1;
        int n = text.length();
        int lineStart = 0;
        while (lineStart < n) {
            int nl = text.indexOf('\n', lineStart);
            int next = (nl < 0) ? n : nl + 1;
            int lineEnd = (nl < 0) ? n : nl;
            if (lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r') lineEnd--;
            String line = text.substring(lineStart, lineEnd);
            if (inMethod) {
                if (METHOD_CLOSE.matcher(line).find()) inMethod = false;
            } else if (METHOD_OPEN.matcher(line).find()) {
                inMethod = true;
            } else {
                Matcher o = REGION_OPEN.matcher(line);
                if (o.find()) {
                    open.push(o.group(1) == null ? "" : o.group(1));
                } else if (REGION_CLOSE.matcher(line).find() && !open.isEmpty()) {
                    String name = open.pop();
                    if (firstClose < 0) firstClose = lineStart;
                    if (publicClose < 0 && isPublicRegion(name)) {
                        publicClose = lineStart;
                        break;
                    }
                }
            }
            lineStart = next;
        }
        return publicClose >= 0 ? publicClose : firstClose;
    }

    private static boolean isPublicRegion(String name) {
        return "ПрограммныйИнтерфейс".equalsIgnoreCase(name) || "Public".equalsIgnoreCase(name);
    }
}
