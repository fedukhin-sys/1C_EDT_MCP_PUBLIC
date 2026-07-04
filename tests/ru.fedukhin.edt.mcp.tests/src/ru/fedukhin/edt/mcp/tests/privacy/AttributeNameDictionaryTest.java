package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.AttributeNameDictionary;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

public class AttributeNameDictionaryTest {
    @Test public void codesAreCounterparty() {
        for (String n : new String[]{"ИНН","КПП","ОГРН","ОГРНИП","ОКПО","ОКАТО","ОКТМО",
                                     "ОКВЭД","ОКОПФ","ОКФС","ОКОГУ","БИК","РасчетныйСчет"}) {
            assertEquals(n, Sensitivity.COUNTERPARTY, AttributeNameDictionary.classify(n));
        }
    }
    @Test public void personalNames() {
        assertEquals(Sensitivity.PERSONAL, AttributeNameDictionary.classify("Паспорт"));
        assertEquals(Sensitivity.PERSONAL, AttributeNameDictionary.classify("СНИЛС"));
        assertEquals(Sensitivity.PERSONAL, AttributeNameDictionary.classify("ДатаРождения"));
        assertEquals(Sensitivity.PERSONAL, AttributeNameDictionary.classify("ТелефонМобильный"));
        assertEquals(Sensitivity.PERSONAL, AttributeNameDictionary.classify("АдресЭлектроннойПочты"));
        assertEquals(Sensitivity.PERSONAL, AttributeNameDictionary.classify("ФИО"));
    }
    @Test public void specialAndBiometric() {
        assertEquals(Sensitivity.SPECIAL, AttributeNameDictionary.classify("Диагноз"));
        assertEquals(Sensitivity.BIOMETRIC, AttributeNameDictionary.classify("Фотография"));
    }
    @Test public void neutralIsNone() {
        assertEquals(Sensitivity.NONE, AttributeNameDictionary.classify("Количество"));
        assertEquals(Sensitivity.NONE, AttributeNameDictionary.classify(null));
    }
}
