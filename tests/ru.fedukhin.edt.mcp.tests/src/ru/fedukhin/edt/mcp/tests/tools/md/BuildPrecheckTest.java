package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.internal.BuildPrecheck;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckMarker;

/**
 * Триаж маркеров перед сборкой .epf: блокирует только то, что реально ломает
 * загрузку модуля в 1cv8 (BSL-компиляция), остальное уезжает в warnings.
 *
 * <p>Границы триажа взяты из CLAUDE.md проекта (раздел про deploy_project) и
 * подтверждены live-smoke Stage 7b: EDT-валидатор кладёт SSL-стиль, deprecation и
 * MdValidation в ту же severity=error, что и compile-блокеры, но на запуск 1cv8 они не влияют.
 */
public class BuildPrecheckTest {

    private static CheckMarker marker(String severity, String checkId, String message) {
        return new CheckMarker("m1", "Demo", "", 10, severity, checkId, "edt", message, null);
    }

    @Test
    public void bslCompileError_isBlocker() {
        BuildPrecheck.Verdict v = BuildPrecheck.of(List.of(
            marker("error", "BslEditor", "Ожидается выражение")));

        assertEquals(1, v.blockers().size());
        assertTrue(v.warnings().isEmpty());
    }

    @Test
    public void sslStyleError_isWarningNotBlocker() {
        BuildPrecheck.Verdict v = BuildPrecheck.of(List.of(
            marker("error", "MdValidationChecker", "некорректные настройки: Внешнее соединение")));

        assertTrue("MdValidation не ломает загрузку .epf", v.blockers().isEmpty());
        assertEquals(1, v.warnings().size());
        assertTrue(v.warnings().get(0), v.warnings().get(0).contains("MdValidationChecker"));
    }

    @Test
    public void bslWarning_isNotBlocker() {
        BuildPrecheck.Verdict v = BuildPrecheck.of(List.of(
            marker("warning", "BslEditor", "Метод устарел")));

        assertTrue("severity=warning у BslEditor — не блокер", v.blockers().isEmpty());
        assertEquals(1, v.warnings().size());
    }

    @Test
    public void infoMarkers_areIgnoredEntirely() {
        BuildPrecheck.Verdict v = BuildPrecheck.of(List.of(
            marker("info", "BslEditor", "мелочь")));

        assertTrue(v.blockers().isEmpty());
        assertTrue("trivial-маркеры не засоряют warnings", v.warnings().isEmpty());
    }

    @Test
    public void mixedMarkers_areSplit() {
        BuildPrecheck.Verdict v = BuildPrecheck.of(List.of(
            marker("error",   "BslEditor",           "Ожидается имя переменной"),
            marker("error",   "MdValidationChecker", "orphan uuid"),
            marker("warning", "BslEditor",           "Метод устарел"),
            marker("info",    "BslEditor",           "мелочь")));

        assertEquals(1, v.blockers().size());
        assertEquals("Ожидается имя переменной", v.blockers().get(0).message());
        assertEquals(2, v.warnings().size());
    }

    /**
     * Live-smoke 2026-07-17: сборка рабочей обработки отклонялась из-за deprecation и SSL-стиля.
     * EDT кладёт эти замечания в severity=error с checkId=BslEditor — так же, как настоящие
     * compile-ошибки, — но 1cv8 они не ломают. С прежним триажем («любой BslEditor+error —
     * блокер») инструмент не собрал бы ни одну типовую обработку: «Метод устарел» есть в любой.
     */
    @Test
    public void bslEditorErrors_thatDoNotBreakCompilation_areWarningsNotBlockers() {
        BuildPrecheck.Verdict v = BuildPrecheck.of(List.of(
            marker("error", "BslEditor", "Метод устарел"),
            marker("error", "BslEditor", "Описание экспортируемой функции должно содержать блок \"Возвращаемое значение\""),
            marker("error", "BslEditor", "Отсутствует включение безопасного режима перед вызовом метода \"Выполнить\""),
            marker("error", "BslEditor", "Тип 'ЗаписьJSON' неопределен [Web-клиент]"),
            marker("error", "BslEditor", "Возвращается недекларируемое свойство: \"Вид, Версия\""),
            marker("error", "BslEditor", "Цикл содержит выполнение запроса")));

        assertEquals("ни одно из этих замечаний не мешает 1cv8 загрузить модуль",
            0, v.blockers().size());
        assertEquals(6, v.warnings().size());
    }

    /** Незнакомое сообщение от BslEditor считаем компиляционным — fail-closed. */
    @Test
    public void unknownBslEditorError_isTreatedAsBlocker() {
        BuildPrecheck.Verdict v = BuildPrecheck.of(List.of(
            marker("error", "BslEditor", "Совершенно новая диагностика платформы")));

        assertEquals(1, v.blockers().size());
    }
}
