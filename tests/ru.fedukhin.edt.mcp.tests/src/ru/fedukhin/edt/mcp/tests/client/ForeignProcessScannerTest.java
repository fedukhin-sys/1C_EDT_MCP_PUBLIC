package ru.fedukhin.edt.mcp.tests.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Set;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.client.internal.ForeignProcessScanner;

/**
 * Разбор командной строки 1cv8: по нему определяется, какую базу держит чужой
 * процесс. Сам обход процессов машины в юнит-тесте недетерминирован, поэтому
 * проверяется именно разбор.
 */
public class ForeignProcessScannerTest {

    @Test
    public void parsesFileInfobaseFromCommandLine() {
        assertEquals("E:\\Bases\\Demo",
                ForeignProcessScanner.infobaseOf("1cv8.exe ENTERPRISE /F\"E:\\Bases\\Demo\" /N user"));
    }

    @Test
    public void parsesServerInfobaseFromCommandLine() {
        assertEquals("srv1\\DemoBase",
                ForeignProcessScanner.infobaseOf("1cv8.exe ENTERPRISE /S\"srv1\\DemoBase\" /N user"));
    }

    /** Путь без кавычек тоже встречается — обрезаем по пробелу. */
    @Test
    public void parsesUnquotedValue() {
        assertEquals("srv1\\DemoBase",
                ForeignProcessScanner.infobaseOf("1cv8.exe ENTERPRISE /Ssrv1\\DemoBase /N user"));
    }

    @Test
    public void unknownCommandLineYieldsNull() {
        assertNull(ForeignProcessScanner.infobaseOf("1cv8.exe DESIGNER"));
        assertNull(ForeignProcessScanner.infobaseOf(null));
    }

    /** Скан не должен падать, даже если ОС не даёт рассмотреть процессы. */
    @Test
    public void scan_neverThrows() {
        assertNotNull(ForeignProcessScanner.scan(Set.of()));
        assertNotNull(ForeignProcessScanner.scan(null));
    }

    /** Собственный процесс тестов не должен попадать в выдачу как чужой. */
    @Test
    public void scan_excludesOwnPids() {
        long self = ProcessHandle.current().pid();
        assertEquals(0, ForeignProcessScanner.scan(Set.of(self)).stream()
                .filter(e -> Long.valueOf(self).equals(e.get("pid")))
                .count());
    }

    /**
     * Так выглядит агент конфигуратора, который поднимает сама EDT: именно он держит
     * информационную базу и превращает чужой deploy_project в тихий no-op. Ключ отделён
     * от значения пробелом — эту форму разбор обязан понимать.
     */
    @Test
    public void parsesSpaceSeparatedServerInfobase() {
        assertEquals("localhost\\Demo", ForeignProcessScanner.infobaseOf(
            "\"C:\\Program Files\\1cv8\\bin\\1cv8.exe\" DESIGNER /S localhost\\Demo /AgentMode"));
    }

    /**
     * На Windows ProcessHandle не отдаёт командную строку чужого процесса, поэтому база
     * остаётся неизвестной. Дозаполнение разовым запросом к ОС — единственный способ
     * узнать, какую именно базу держит чужой процесс.
     */
    @Test
    public void scan_fillsInfobaseFromFallbackWhenCommandLineIsUnavailable() {
        java.util.Map<Long, String> byPid = java.util.Map.of(
            12608L, "1cv8.exe DESIGNER /S localhost\\Demo /AgentMode");

        java.util.List<java.util.Map<String, Object>> out =
            ForeignProcessScanner.scan(Set.of(), () -> byPid);

        for (java.util.Map<String, Object> e : out) {
            if (Long.valueOf(12608L).equals(e.get("pid"))) {
                assertEquals("localhost\\Demo", e.get("infobase"));
            }
        }
    }

    /** Осечка запроса не должна ронять инвентаризацию — поле просто останется пустым. */
    @Test
    public void scan_survivesFallbackFailure() {
        assertNotNull(ForeignProcessScanner.scan(Set.of(), () -> {
            throw new IllegalStateException("CIM недоступен");
        }));
    }

    /** Без источника командных строк скан обязан работать как раньше. */
    @Test
    public void scan_withoutFallback_doesNotThrow() {
        assertNotNull(ForeignProcessScanner.scan(Set.of(), null));
    }
}
