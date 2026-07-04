package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.Pseudonymizer;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

public class PseudonymizerTest {
    private final Pseudonymizer p = new Pseudonymizer("test-key".getBytes(StandardCharsets.UTF_8));

    @Test public void stableTokenForSameValue() {
        String a = p.token(Sensitivity.PERSONAL, "Иванов И.И.");
        String b = p.token(Sensitivity.PERSONAL, "иванов  и.и."); // нормализация регистра/пробелов
        assertEquals(a, b);
        assertTrue(a.startsWith("Физлицо#"));
    }
    @Test public void differentValuesDifferentTokens() {
        assertNotEquals(p.token(Sensitivity.COUNTERPARTY, "ООО Ромашка"),
                        p.token(Sensitivity.COUNTERPARTY, "ООО Лютик"));
    }
    @Test public void specialCategoryFullyHidden() {
        String t = p.token(Sensitivity.SPECIAL, "сахарный диабет");
        assertEquals("[спец. категория ПДн скрыта]", t);
    }
    @Test public void keyMatters() {
        Pseudonymizer other = new Pseudonymizer("другой-ключ".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(p.token(Sensitivity.PERSONAL, "Иванов"),
                        other.token(Sensitivity.PERSONAL, "Иванов"));
    }
    @Test public void tokenHasEightHexChars() {
        String t = p.token(Sensitivity.PERSONAL, "Иванов");
        String hex = t.substring(t.indexOf('#') + 1);
        assertEquals(8, hex.length());
        assertTrue(hex.matches("[0-9a-f]{8}"));
    }
}
