package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestCatalog;

/**
 * Состав тестов для run_tests (модуль и все модули проекта).
 *
 * <p>Серверный раннер умел только один метод: run_tests с moduleFqn и без него молча отвечал
 * {@code {passed:0, failed:0, tests:[]}}. Тесты определяются по исходникам проекта той же
 * конвенцией, что у create_test_module/add_test_method: регистрация
 * {@code ЮнитТесты.ДобавитьТест("Тест_…")} в {@code ИсполняемыеСценарии}, а если модуль ничего не
 * регистрирует — экспортные процедуры без параметров с префиксом {@code Тест_}/{@code Test_}.
 */
public class TestCatalogTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path project;

    @Before
    public void setUp() throws Exception {
        project = tmp.newFolder("Demo.Тесты").toPath();
    }

    private void module(String name, String text) throws Exception {
        Path dir = project.resolve("src").resolve("CommonModules").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Module.bsl"), String.valueOf((char) 0xFEFF) + text, StandardCharsets.UTF_8);
    }

    private static final String REGISTERED = ""
        + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
        + "\tЮнитТесты.ДобавитьТест(\"Тест_Первый\");\r\n"
        + "\t// ЮнитТесты.ДобавитьТест(\"Тест_Закомментирован\");\r\n"
        + "\tЮнитТесты.ДобавитьТест(\"Тест_Второй\");\r\n"
        + "КонецПроцедуры\r\n\r\n"
        + "Процедура Тест_Первый() Экспорт\r\nКонецПроцедуры\r\n"
        + "Процедура Тест_Второй() Экспорт\r\nКонецПроцедуры\r\n"
        + "Процедура Тест_НеЗарегистрирован() Экспорт\r\nКонецПроцедуры\r\n";

    private static final String BY_PREFIX = ""
        + "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\n"
        + "КонецПроцедуры\r\n\r\n"
        + "Процедура Тест_Диагностика() Экспорт\r\nКонецПроцедуры\r\n"
        + "// Процедура Тест_ВКомментарии() Экспорт\r\n"
        + "Процедура Тест_СПараметром(Значение) Экспорт\r\nКонецПроцедуры\r\n"
        + "Процедура Тест_НеЭкспортный()\r\nКонецПроцедуры\r\n"
        + "Процедура ТЕСТ_РегистрНеВажен() Экспорт\r\nКонецПроцедуры\r\n"
        + "Процедура Помощник() Экспорт\r\nКонецПроцедуры\r\n";

    @Test
    public void testsInModule_registeredOnesWin() {
        assertEquals(List.of("Тест_Первый", "Тест_Второй"), TestCatalog.testsInModule(REGISTERED));
    }

    @Test
    public void testsInModule_withoutRegistrations_exportedNoArgPrefixed() {
        assertEquals(List.of("Тест_Диагностика", "ТЕСТ_РегистрНеВажен"), TestCatalog.testsInModule(BY_PREFIX));
    }

    @Test
    public void testsInModule_english() {
        String text = "Procedure ExecutableScenarios(UnitTests) Export\r\nEndProcedure\r\n"
            + "Procedure Test_One() Export\r\nEndProcedure\r\n";
        assertEquals(List.of("Test_One"), TestCatalog.testsInModule(text));
    }

    @Test
    public void module_listsItsTests() throws Exception {
        module("ДемоТест_Закупки", BY_PREFIX);
        assertEquals(List.of("ДемоТест_Закупки.Тест_Диагностика", "ДемоТест_Закупки.ТЕСТ_РегистрНеВажен"),
            TestCatalog.collect(project, "CommonModule.ДемоТест_Закупки").tests());
    }

    @Test
    public void module_withoutTests_refusesWithReason() throws Exception {
        module("ДемоТест_Пусто", "Процедура ИсполняемыеСценарии(ЮнитТесты) Экспорт\r\nКонецПроцедуры\r\n");
        try {
            TestCatalog.collect(project, "ДемоТест_Пусто");
            fail("пустой набор — не молчаливые 0 тестов, а отказ");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ДемоТест_Пусто"));
        }
    }

    @Test
    public void module_missing_refuses() {
        try {
            TestCatalog.collect(project, "CommonModule.НетТакого");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("НетТакого"));
        }
    }

    /** Клиентские тесты (модули «…Клиент») идут только по одному — у них свой протокол на клиенте. */
    @Test
    public void module_client_refusesAndPointsToRunTestMethod() throws Exception {
        module("ДемоТест_ФормаКлиент", BY_PREFIX);
        try {
            TestCatalog.collect(project, "ДемоТест_ФормаКлиент");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("run_test_method"));
        }
    }

    @Test
    public void all_testModulesOnly_runnerAndClientSkipped() throws Exception {
        module("ДемоТест_А", REGISTERED);
        module("ДемоТест_Б", BY_PREFIX);
        module("ДемоТест_ФормаКлиент", BY_PREFIX);
        module("EDT_MCP_TestRunner_Сервер_Demo", "Функция ВыполнитьТесты(Селектор) Экспорт\r\nКонецФункции\r\n");
        module("ОбщегоНазначенияДемо", "Процедура Тест_НеТестовыйМодуль() Экспорт\r\nКонецПроцедуры\r\n");

        TestCatalog.Selection all = TestCatalog.collect(project, null);

        assertEquals(List.of("ДемоТест_А.Тест_Первый", "ДемоТест_А.Тест_Второй",
            "ДемоТест_Б.Тест_Диагностика", "ДемоТест_Б.ТЕСТ_РегистрНеВажен"), all.tests());
        assertEquals(List.of("ДемоТест_ФормаКлиент"), all.skippedClientModules());
    }

    @Test
    public void all_noTestModules_refuses() throws Exception {
        module("ОбщегоНазначенияДемо", "Процедура Сделать() Экспорт\r\nКонецПроцедуры\r\n");
        try {
            TestCatalog.collect(project, null);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ИсполняемыеСценарии"));
        }
    }
}
