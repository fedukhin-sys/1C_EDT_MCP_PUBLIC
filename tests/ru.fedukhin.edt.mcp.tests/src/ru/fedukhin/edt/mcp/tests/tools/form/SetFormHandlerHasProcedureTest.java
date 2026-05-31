package ru.fedukhin.edt.mcp.tests.tools.form;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.form.SetFormHandlerTool;

/**
 * BUG-06: idempotent stub append — the regex matcher must detect an existing
 * {@code Процедура HandlerName(} declaration so a re-bind does not duplicate
 * the procedure body.
 */
public class SetFormHandlerHasProcedureTest {

    private static boolean hasProcedure(String src, String name) throws Exception {
        Method m = SetFormHandlerTool.class.getDeclaredMethod(
                "hasProcedure", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, src, name);
    }

    @Test public void empty_returnsFalse() throws Exception {
        assertFalse(hasProcedure("", "ПриОткрытии"));
        assertFalse(hasProcedure(null, "ПриОткрытии"));
    }

    @Test public void detects_simpleDeclaration() throws Exception {
        String src = "&НаКлиенте\nПроцедура ПриОткрытии(Отказ)\nКонецПроцедуры\n";
        assertTrue(hasProcedure(src, "ПриОткрытии"));
    }

    @Test public void detects_indentedDeclaration() throws Exception {
        String src = "\t Процедура ПриОткрытии(Отказ)\n\tКонецПроцедуры\n";
        assertTrue(hasProcedure(src, "ПриОткрытии"));
    }

    @Test public void detects_caseInsensitive() throws Exception {
        String src = "ПРОЦЕДУРА ПриОткрытии(Отказ)\nКонецПроцедуры\n";
        assertTrue(hasProcedure(src, "ПриОткрытии"));
    }

    @Test public void doesNotMatch_differentName() throws Exception {
        String src = "Процедура ПриОткрытии2(Отказ)\nКонецПроцедуры\n";
        assertFalse(hasProcedure(src, "ПриОткрытии"));
    }

    @Test public void doesNotMatch_substring() throws Exception {
        String src = "Процедура МойПриОткрытии(Отказ)\nКонецПроцедуры\n";
        assertFalse(hasProcedure(src, "ПриОткрытии"));
    }

    @Test public void doesNotMatch_inComment() throws Exception {
        String src = "// Процедура ПриОткрытии(Отказ)\n";
        assertFalse(hasProcedure(src, "ПриОткрытии"));
    }

    @Test public void detects_amongOtherProcedures() throws Exception {
        String src = "Процедура Первая() КонецПроцедуры\n"
                   + "&НаСервере\nПроцедура ПриСозданииНаСервере(Отказ, СтандартнаяОбработка)\n"
                   + "КонецПроцедуры\n"
                   + "Процедура Последняя() КонецПроцедуры\n";
        assertTrue(hasProcedure(src, "ПриСозданииНаСервере"));
        assertTrue(hasProcedure(src, "Первая"));
        assertTrue(hasProcedure(src, "Последняя"));
        assertFalse(hasProcedure(src, "Несуществующая"));
    }
}
