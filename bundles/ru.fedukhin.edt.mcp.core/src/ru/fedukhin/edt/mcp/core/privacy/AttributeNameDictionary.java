package ru.fedukhin.edt.mcp.core.privacy;

import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

/** Классификация по ИМЕНИ реквизита/переменной. Регистронезависимо, по подстроке. */
public final class AttributeNameDictionary {

    private static final String[] SPECIAL = {
        "диагноз", "болезн", "здоровь", "инвалид", "судим", "национальн", "вероисповед", "религ"
    };
    private static final String[] BIOMETRIC = {
        "фотограф", "фото", "биометр", "отпечаток", "скан"
    };
    private static final String[] COUNTERPARTY = {
        "инн", "кпп", "огрн", "огрнип", "окпо", "окато", "октмо", "оквэд", "окопф",
        "окфс", "окогу", "бик", "расчетныйсчет", "корсчет", "корреспондентскийсчет", "номерсчета"
    };
    private static final String[] PERSONAL = {
        "паспорт", "снилс", "фио", "фамили", "отчеств", "датарожден", "деньрожден",
        "телефон", "email", "почт", "адрес", "прописк", "местожительств", "документудостовер"
    };

    private AttributeNameDictionary() {}

    public static Sensitivity classify(String name) {
        if (name == null) return Sensitivity.NONE;
        String n = name.toLowerCase().replace("ё", "е");
        if (containsAny(n, SPECIAL)) return Sensitivity.SPECIAL;
        if (containsAny(n, BIOMETRIC)) return Sensitivity.BIOMETRIC;
        if (containsAny(n, COUNTERPARTY)) return Sensitivity.COUNTERPARTY;
        if (containsAny(n, PERSONAL)) return Sensitivity.PERSONAL;
        return Sensitivity.NONE;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String s : needles) if (haystack.contains(s)) return true;
        return false;
    }
}
