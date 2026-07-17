package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.ContentPatterns;

public class ContentPatternsTest {
    @Test public void masksEmailAndPhoneAndSnils() {
        String out = ContentPatterns.maskInline("Контакт ivan@mail.ru тел +7 (912) 345-67-89 СНИЛС 112-233-445 95");
        assertFalse(out.contains("ivan@mail.ru"));
        assertFalse(out.contains("345-67-89"));
        assertFalse(out.contains("112-233-445 95"));
        assertTrue(out.contains("Контакт"));
    }
    @Test public void masksInnAndOgrn() {
        assertFalse(ContentPatterns.maskInline("ИНН 7707083893").contains("7707083893"));
        assertFalse(ContentPatterns.maskInline("ОГРН 1027700132195").contains("1027700132195"));
    }
    @Test public void doesNotMaskInnocentNumbers() {
        // цена, количество, короткие номера — не трогаем
        assertEquals("Сумма 1500 руб, кол-во 42", ContentPatterns.maskInline("Сумма 1500 руб, кол-во 42"));
        assertFalse(ContentPatterns.hasPii("год 2026, страниц 12"));
    }
    @Test public void hasPiiNullIsSafe() {
        assertFalse(ContentPatterns.hasPii(null));
        assertNull(ContentPatterns.maskInline(null));
    }
    @Test public void innLabeledAsInnNotPassport() {
        assertTrue(ContentPatterns.maskInline("ИНН 7707083893").contains("[скрыто:инн]"));
    }
    @Test public void spacedPassportLabeled() {
        assertTrue(ContentPatterns.maskInline("паспорт 12 34 567890").contains("[скрыто:паспорт]"));
    }

    /** CLAUDE.md заявлял охват «БИК/р/с», а паттернов на них не было вовсе. */
    @Test public void masksBankAccountAndBik() {
        String masked = ContentPatterns.maskInline("р/с 40702810900000012345, БИК 044525225");
        assertFalse(masked.contains("40702810900000012345"));
        assertTrue(masked.contains("[скрыто:счёт]"));
        assertFalse(masked.contains("044525225"));
        assertTrue(masked.contains("[скрыто:бик]"));
    }

    @Test public void masksCardNumber() {
        assertFalse(ContentPatterns.maskInline("карта 4276380012345678").contains("4276380012345678"));
        assertTrue(ContentPatterns.maskInline("карта 4276 3800 1234 5678").contains("[скрыто:карта]"));
    }

    /** СНИЛС без разделителей — 11 цифр. */
    @Test public void masksSnilsWithoutSeparators() {
        assertFalse(ContentPatterns.maskInline("СНИЛС 11223344595").contains("11223344595"));
    }

    /** 8XXXXXXXXXX — тоже 11 цифр, но это телефон: метка обязана быть телефонной. */
    @Test public void plainPhone_isLabelledAsPhone_notSnils() {
        assertTrue(ContentPatterns.maskInline("тел 89161234567").contains("[скрыто:телефон]"));
    }
}
