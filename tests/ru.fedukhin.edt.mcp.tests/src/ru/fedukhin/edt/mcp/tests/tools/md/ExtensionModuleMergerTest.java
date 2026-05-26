package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.internal.ExtensionModuleMerger;
import ru.fedukhin.edt.mcp.tools.md.internal.ExtensionModuleMerger.Action;
import ru.fedukhin.edt.mcp.tools.md.internal.ExtensionModuleMerger.Result;

/**
 * Unit-тесты на {@link ExtensionModuleMerger} — dedup-логику для
 * {@code add_extension_method_override}.
 */
public class ExtensionModuleMergerTest {

    @Test
    public void appendToEmptyModule() {
        Result r = ExtensionModuleMerger.merge("", procSource("Расш1_ПриЗаписи_После", "  // A"));
        assertEquals(Action.APPENDED, r.action());
        assertEquals("Расш1_ПриЗаписи_После", r.procName());
        assertTrue(r.text().contains("Процедура Расш1_ПриЗаписи_После"));
        assertTrue(r.text().contains("// A"));
        assertTrue(r.text().endsWith("\n"));
    }

    @Test
    public void appendsNewProcedureWhenNoDuplicate() {
        String existing = procSource("Расш1_ПриЗаписи_Перед", "  // before");
        Result r = ExtensionModuleMerger.merge(existing, procSource("Расш1_ПриЗаписи_После", "  // after"));
        assertEquals(Action.APPENDED, r.action());
        assertEquals("Расш1_ПриЗаписи_После", r.procName());
        assertTrue("оригинал сохранён", r.text().contains("Процедура Расш1_ПриЗаписи_Перед"));
        assertTrue("новая добавлена", r.text().contains("Процедура Расш1_ПриЗаписи_После"));
        // Blank-line separator между процедурами
        assertTrue(r.text().contains("КонецПроцедуры\n\n&"));
    }

    @Test
    public void mergesBodyWhenProcedureNameAlreadyExists() {
        String existing = procSource("Расш1_ПриЗаписи_После", "  // body1");
        Result r = ExtensionModuleMerger.merge(existing, procSource("Расш1_ПриЗаписи_После", "  // body2"));
        assertEquals(Action.MERGED, r.action());
        assertEquals("Расш1_ПриЗаписи_После", r.procName());
        // Должна быть только ОДНА процедура с таким именем
        int firstHeader = r.text().indexOf("Процедура Расш1_ПриЗаписи_После");
        assertTrue("заголовок есть", firstHeader >= 0);
        int secondHeader = r.text().indexOf("Процедура Расш1_ПриЗаписи_После", firstHeader + 1);
        assertEquals("дубликата быть не должно", -1, secondHeader);
        // body1 + body2 присутствуют
        assertTrue(r.text().contains("// body1"));
        assertTrue(r.text().contains("// body2"));
        // Маркер merge виден
        assertTrue(r.text().contains("merged from MCP add_extension_method_override"));
        // body2 идёт ПОСЛЕ body1
        assertTrue("body2 должен быть после body1",
                r.text().indexOf("// body2") > r.text().indexOf("// body1"));
        // КонецПроцедуры остаётся ровно одна
        int firstEnd = r.text().indexOf("КонецПроцедуры");
        int secondEnd = r.text().indexOf("КонецПроцедуры", firstEnd + 1);
        assertEquals("одна КонецПроцедуры", -1, secondEnd);
    }

    @Test
    public void mergesFunctionsToo() {
        String existing = "&Перед(\"X\")\nФункция Расш1_X_Перед() Экспорт\n  Возврат 1;\nКонецФункции\n";
        String newSrc = "&Перед(\"X\")\nФункция Расш1_X_Перед() Экспорт\n  Возврат 2;\nКонецФункции\n";
        Result r = ExtensionModuleMerger.merge(existing, newSrc);
        assertEquals(Action.MERGED, r.action());
        assertEquals("Расш1_X_Перед", r.procName());
        assertTrue(r.text().contains("Возврат 1;"));
        assertTrue(r.text().contains("Возврат 2;"));
        int firstEnd = r.text().indexOf("КонецФункции");
        assertEquals("ровно одна КонецФункции", -1, r.text().indexOf("КонецФункции", firstEnd + 1));
    }

    @Test
    public void differentProcedureNamesNotMerged() {
        // Не должно ложно срабатывать для процедур с разными именами но одной базовой
        String existing = procSource("Расш1_ПриЗаписи_Перед", "  // before");
        String newSrc   = procSource("Расш2_ПриЗаписи_Перед", "  // also before, second ext");
        Result r = ExtensionModuleMerger.merge(existing, newSrc);
        assertEquals(Action.APPENDED, r.action());
        assertTrue(r.text().contains("Процедура Расш1_ПриЗаписи_Перед"));
        assertTrue(r.text().contains("Процедура Расш2_ПриЗаписи_Перед"));
    }

    @Test
    public void sourceWithoutProcedure_appended() {
        // Например, юзер передал только аннотацию без Процедура — приложение
        // лежит на нём, мы просто append.
        Result r = ExtensionModuleMerger.merge("// existing\n", "// just a comment\n");
        assertEquals(Action.APPENDED, r.action());
        assertNull(r.procName());
        assertTrue(r.text().contains("// existing"));
        assertTrue(r.text().contains("// just a comment"));
    }

    @Test
    public void emptyNewSource_returnsTrimmedExisting() {
        Result r = ExtensionModuleMerger.merge("// keep\n\n\n", "");
        assertEquals(Action.APPENDED, r.action());
        assertEquals("// keep", r.text());
    }

    private static String procSource(String procName, String bodyLine) {
        return "&После(\"ПриЗаписи\")\n"
                + "Процедура " + procName + "(Отказ)\n"
                + bodyLine + "\n"
                + "КонецПроцедуры\n";
    }
}
