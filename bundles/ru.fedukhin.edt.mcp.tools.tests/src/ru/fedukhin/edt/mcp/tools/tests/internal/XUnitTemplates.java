package ru.fedukhin.edt.mcp.tools.tests.internal;

public final class XUnitTemplates {

    public enum Language { RU, EN }

    private XUnitTemplates() {}

    /** Skeleton body for a new test module. */
    public static String moduleBody(Language lang) {
        if (lang == Language.RU) {
            return ""
                + "#Область ПрограммныйИнтерфейс\r\n\r\n"
                + "// Возвращает массив тестовых сценариев модуля.\r\n"
                + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
                + "КонецПроцедуры\r\n\r\n"
                + "#КонецОбласти\r\n";
        }
        return ""
            + "#Region Public\r\n\r\n"
            + "// Returns test scenarios in this module.\r\n"
            + "Procedure ExecutableScenarios(UnitTests) Export\r\n"
            + "EndProcedure\r\n\r\n"
            + "#EndRegion\r\n";
    }

    /**
     * Имя тестового метода с префиксом {@code Тест_}/{@code Test_} ровно один раз.
     * Если клиент уже передал имя с префиксом (в любом регистре — идентификаторы BSL
     * регистронезависимы), оно возвращается как есть: раньше префикс добавлялся повторно
     * ({@code Тест_Тест_…}), и метод в модуле не совпадал с {@code fqn} из ответа инструмента.
     */
    public static String fqn(String methodName, Language lang) {
        String prefix = prefix(lang);
        return methodName.regionMatches(true, 0, prefix, 0, prefix.length())
                ? methodName
                : prefix + methodName;
    }

    /**
     * Текст тестового метода: заголовок с {@code Экспорт}/{@code Export}, тело и
     * {@code КонецПроцедуры}/{@code EndProcedure} на отдельной строке. Переводы строк — {@code eol}
     * (стиль файла); тело приводится к тому же стилю и всегда завершается переводом строки,
     * иначе последний оператор склеивался с {@code КонецПроцедуры}.
     *
     * @param fqn      полное имя метода (уже с префиксом, см. {@link #fqn(String, Language)})
     * @param userBody тело метода или {@code null}/пусто — тогда заглушка {@code // TODO}
     * @param eol      перевод строки файла: {@code "\r\n"} или {@code "\n"}
     */
    public static String methodBody(String fqn, Language lang, String userBody, String eol) {
        String header = (lang == Language.RU) ? "Процедура " : "Procedure ";
        String footer = (lang == Language.RU) ? "КонецПроцедуры" : "EndProcedure";
        String exportKw = (lang == Language.RU) ? " Экспорт" : " Export";
        return header + fqn + "()" + exportKw + eol + normalizeBody(userBody, lang, eol) + footer + eol;
    }

    private static String normalizeBody(String userBody, Language lang, String eol) {
        if (userBody == null || userBody.isBlank()) {
            return (lang == Language.RU ? "\t// TODO: написать тест" : "\t// TODO: write test") + eol;
        }
        String body = userBody.replace("\r\n", "\n").replace('\r', '\n').replace("\n", eol);
        return body.endsWith(eol) ? body : body + eol;
    }

    /** Returns the prefix to apply to a method name based on language. */
    public static String prefix(Language lang) {
        return (lang == Language.RU) ? "Тест_" : "Test_";
    }
}
