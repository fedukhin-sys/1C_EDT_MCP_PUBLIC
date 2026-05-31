package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.internal.ObjectModuleGuard;

public class ObjectModuleGuardTest {

    @Test
    public void isGuardedModule_objectSideModules() {
        assertTrue(ObjectModuleGuard.isGuardedModule("src/Documents/X/ObjectModule.bsl"));
        assertTrue(ObjectModuleGuard.isGuardedModule("src/Catalogs/X/ManagerModule.bsl"));
        assertTrue(ObjectModuleGuard.isGuardedModule("src/InformationRegisters/X/RecordSetModule.bsl"));
        assertFalse(ObjectModuleGuard.isGuardedModule("src/CommonModules/X/Module.bsl"));
        assertFalse(ObjectModuleGuard.isGuardedModule("src/Documents/X/Forms/Y/Module.bsl"));
    }

    @Test
    public void detectGuard_singleWrapper() {
        String base = """
                #Если Сервер Или ТолстыйКлиентОбычноеПриложение Или ВнешнееСоединение Тогда

                #Область ОбработчикиСобытий

                Процедура ОбработкаПроведения(Отказ, Режим)
                КонецПроцедуры

                #КонецОбласти

                #КонецЕсли
                """;
        String[] guard = ObjectModuleGuard.detectGuard(base);
        assertArrayEquals(new String[] {
                "#Если Сервер Или ТолстыйКлиентОбычноеПриложение Или ВнешнееСоединение Тогда",
                "#КонецЕсли" }, guard);
    }

    @Test
    public void detectGuard_nestedWrapper() {
        String base = """
                #Если НЕ МобильныйАвтономныйСервер Тогда
                #Если Сервер Или ВнешнееСоединение Тогда

                Процедура П()
                КонецПроцедуры

                #КонецЕсли
                #КонецЕсли
                """;
        String[] guard = ObjectModuleGuard.detectGuard(base);
        assertArrayEquals(new String[] {
                "#Если НЕ МобильныйАвтономныйСервер Тогда\n#Если Сервер Или ВнешнееСоединение Тогда",
                "#КонецЕсли\n#КонецЕсли" }, guard);
    }

    @Test
    public void detectGuard_skipsLicenseHeader() {
        String base = """
                // Лицензия, строка 1
                // строка 2

                #Если Сервер Тогда
                Процедура П()
                КонецПроцедуры
                #КонецЕсли
                """;
        String[] guard = ObjectModuleGuard.detectGuard(base);
        assertArrayEquals(new String[] { "#Если Сервер Тогда", "#КонецЕсли" }, guard);
    }

    @Test
    public void detectGuard_notWrapped_returnsNull() {
        assertNull(ObjectModuleGuard.detectGuard("Процедура П()\nКонецПроцедуры\n"));
        assertNull(ObjectModuleGuard.detectGuard(""));
        assertNull(ObjectModuleGuard.detectGuard(null));
    }

    @Test
    public void detectGuard_unbalanced_returnsNull() {
        // a #Если block followed by unguarded code — not a clean whole-module wrapper
        String base = """
                #Если Сервер Тогда
                Процедура А()
                КонецПроцедуры
                #КонецЕсли

                Процедура Б()
                КонецПроцедуры
                """;
        assertNull(ObjectModuleGuard.detectGuard(base));
    }

    @Test
    public void wrap_wrapsSourceBetweenPreambleAndPostamble() {
        String[] guard = { "#Если Сервер Тогда", "#КонецЕсли" };
        String wrapped = ObjectModuleGuard.wrap("&После(\"ПриЗаписи\")\nПроцедура Расш_ПриЗаписи()\nКонецПроцедуры", guard);
        assertTrue(wrapped.startsWith("#Если Сервер Тогда\n\n&После"));
        assertTrue(wrapped.contains("Процедура Расш_ПриЗаписи()"));
        assertTrue(wrapped.trim().endsWith("#КонецЕсли"));
    }

    @Test
    public void alreadyGuarded_detectsLeadingDirective() {
        assertTrue(ObjectModuleGuard.alreadyGuarded("#Если Сервер Тогда\nПроцедура П()\nКонецПроцедуры\n#КонецЕсли"));
        assertFalse(ObjectModuleGuard.alreadyGuarded("&После(\"ПриЗаписи\")\nПроцедура П()\nКонецПроцедуры"));
        // an inner #Если inside the body must not count as "already guarded"
        assertFalse(ObjectModuleGuard.alreadyGuarded(
                "&ИзменениеИКонтроль(\"X\")\nПроцедура П()\n#Если Сервер Тогда\nКонецПроцедуры"));
    }
}
