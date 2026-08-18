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
}
