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

    /** Boilerplate for a new test method with optional body. */
    public static String methodBody(String name, Language lang, String userBody) {
        String prefix = (lang == Language.RU) ? "Тест_" : "Test_";
        String header = (lang == Language.RU) ? "Процедура " : "Procedure ";
        String footer = (lang == Language.RU) ? "КонецПроцедуры" : "EndProcedure";
        String exportKw = (lang == Language.RU) ? " Экспорт" : " Export";
        String body = (userBody != null && !userBody.isBlank()) ? userBody
                : (lang == Language.RU ? "\t// TODO: написать тест\r\n" : "\t// TODO: write test\r\n");
        return header + prefix + name + "()" + exportKw + "\r\n" + body + footer + "\r\n";
    }

    /** Returns the prefix to apply to a method name based on language. */
    public static String prefix(Language lang) {
        return (lang == Language.RU) ? "Тест_" : "Test_";
    }
}
