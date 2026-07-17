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
    // Банковский счёт (р/с, к/с) — ровно 20 цифр, с разделителями или без.
    // CLAUDE.md заявлял охват «БИК/р/с», а паттернов на них не было вовсе.
    private static final Pattern ACCOUNT = Pattern.compile(
        "\\b\\d{20}\\b|\\b\\d{5}[\\s\\-]\\d{3}[\\s\\-]\\d[\\s\\-]\\d{4}[\\s\\-]\\d{7}\\b");
    // Карта: 16 цифр подряд либо группами по 4.
    private static final Pattern CARD = Pattern.compile(
        "\\b\\d{16}\\b|\\b\\d{4}[\\s\\-]\\d{4}[\\s\\-]\\d{4}[\\s\\-]\\d{4}\\b");
    // СНИЛС без разделителей — 11 цифр. Идёт ПОСЛЕ телефона: 8XXXXXXXXXX это тоже 11 цифр,
    // и телефон должен получить свою метку первым.
    private static final Pattern SNILS_PLAIN = Pattern.compile("\\b\\d{11}\\b");
    // БИК — 9 цифр, российские начинаются на 04 или 05. Сужено до этих префиксов:
    // «любое 9-значное число» давало бы слишком много ложных срабатываний.
    private static final Pattern BIK = Pattern.compile("\\b0[45]\\d{7}\\b");

    // Порядок важен: более специфичные и более длинные — раньше, иначе короткий
    // паттерн заберёт часть длинного номера и повесит неверную метку.
    private static final Pattern[][] LABELLED = {
        {EMAIL}, {SNILS}, {PHONE}, {PASSPORT}, {ACCOUNT}, {CARD}, {OGRN}, {SNILS_PLAIN}, {INN}, {BIK}
    };
    private static final String[] LABELS = {
        "email", "снилс", "телефон", "паспорт", "счёт", "карта", "огрн", "снилс", "инн", "бик"
    };

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
