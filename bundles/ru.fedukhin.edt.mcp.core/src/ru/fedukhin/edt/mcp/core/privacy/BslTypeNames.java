package ru.fedukhin.edt.mcp.core.privacy;

import java.util.Map;
import java.util.Optional;

/** Преобразует имя BSL-типа значения (Ссылка/Объект) в полное имя объекта метаданных. */
public final class BslTypeNames {

    /** BSL-префикс коллекции → префикс полного имени объекта. */
    private static final Map<String, String> COLLECTION = Map.ofEntries(
        Map.entry("Справочник", "Справочник"),
        Map.entry("Документ", "Документ"),
        Map.entry("Перечисление", "Перечисление"),
        Map.entry("ПланВидовХарактеристик", "ПланВидовХарактеристик"),
        Map.entry("ПланСчетов", "ПланСчетов"),
        Map.entry("ПланВидовРасчета", "ПланВидовРасчета"),
        Map.entry("РегистрСведений", "РегистрСведений"),
        Map.entry("РегистрНакопления", "РегистрНакопления"),
        Map.entry("РегистрБухгалтерии", "РегистрБухгалтерии"),
        Map.entry("БизнесПроцесс", "БизнесПроцесс"),
        Map.entry("Задача", "Задача")
    );

    /** Суффиксы value-типа, которые надо снять, чтобы получить коллекцию. */
    private static final String[] SUFFIXES = {"Ссылка", "Объект", "Выборка", "СписокНастроек", "МенеджерЗаписи"};

    private BslTypeNames() {}

    public static Optional<String> objectFullName(String bslType) {
        if (bslType == null || bslType.isBlank()) return Optional.empty();
        int dot = bslType.indexOf('.');
        if (dot <= 0) return Optional.empty();
        String head = bslType.substring(0, dot);       // "СправочникСсылка"
        String name = bslType.substring(dot + 1);      // "Контрагенты"
        for (String suf : SUFFIXES) {
            if (head.endsWith(suf)) {
                String coll = head.substring(0, head.length() - suf.length());
                String prefix = COLLECTION.get(coll);
                if (prefix != null) return Optional.of(prefix + "." + name);
            }
        }
        return Optional.empty();
    }
}
