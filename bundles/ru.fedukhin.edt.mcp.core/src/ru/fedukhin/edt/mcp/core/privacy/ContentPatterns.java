package ru.fedukhin.edt.mcp.core.privacy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Content-regex подстраховка (слой 3, fail-closed) для свободных строк. */
public final class ContentPatterns {

    // email
    private static final Pattern EMAIL = Pattern.compile(
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // СНИЛС: 123-456-789 01
    private static final Pattern SNILS = Pattern.compile(
        "\\b\\d{3}-\\d{3}-\\d{3}\\s?\\d{2}\\b");
    // телефон: +7/8 с разделителями, минимум 10 цифр
    private static final Pattern PHONE = Pattern.compile(
        "(?:\\+7|8)[\\s\\-()]*\\d{3}[\\s\\-()]*\\d{3}[\\s\\-()]*\\d{2}[\\s\\-()]*\\d{2}\\b");
    // ОГРН(13)/ОГРНИП(15)
    private static final Pattern OGRN = Pattern.compile("\\b\\d{13}\\b|\\b\\d{15}\\b");
    // ИНН юрлица(10)/физлица(12) — только как отдельное «слово».
    // Примечание: любое «голое» 10/12-значное число (номер заказа, счёта,
    // штрихкод и т.п.) будет излишне замаскировано как ИНН — это осознанный
    // fail-closed компромисс для этого backstop-слоя.
    private static final Pattern INN = Pattern.compile("\\b\\d{10}\\b|\\b\\d{12}\\b");
    // паспорт РФ: 4 + 6 цифр, разделённые пробелом (реальный разделитель серии/номера).
    // Без обязательного пробела «голое» 10-значное число совпадало бы и с
    // паспортом, и с ИНН — паспорт шёл в цикле первым и маскировал бы его
    // неверной меткой, оставляя ветку ИНН(10) мёртвым кодом.
    private static final Pattern PASSPORT = Pattern.compile("\\b\\d{4}\\s\\d{6}\\b|\\b\\d{2}\\s\\d{2}\\s\\d{6}\\b");

    private static final Pattern[][] LABELLED = {
        {EMAIL},  {SNILS}, {PHONE}, {PASSPORT}, {OGRN}, {INN}
    };
    private static final String[] LABELS = {"email", "снилс", "телефон", "паспорт", "огрн", "инн"};

    private ContentPatterns() {}

    public static boolean hasPii(String text) {
        if (text == null) return false;
        return !maskInline(text).equals(text);
    }

    public static String maskInline(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (int i = 0; i < LABELLED.length; i++) {
            out = replace(out, LABELLED[i][0], "[скрыто:" + LABELS[i] + "]");
        }
        return out;
    }

    private static String replace(String text, Pattern p, String repl) {
        Matcher m = p.matcher(text);
        return m.find() ? m.replaceAll(Matcher.quoteReplacement(repl)) : text;
    }
}
