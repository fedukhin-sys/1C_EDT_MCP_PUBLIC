package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.InfobaseType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fedukhin.edt.mcp.tools.testrun.internal.SessionErrorProbe;

/**
 * Детектор необработанной ошибки сеанса прогона по журналу регистрации.
 *
 * <p>Поводы — 10.10 и 12.10.2026: тестовый модуль не компилировался в рантайме (EDT ошибку не
 * видит), клиент показывал окно ошибки, обработчик раннера не выполнялся, и run_test_method ждал
 * до таймаута. Платформа при этом пишет в журнал {@code _$PerformError$_} — его и ищем, но только
 * в сеансах, начатых после запуска прогона клиентом этого компьютера.
 *
 * <p>Записи — в настоящем многострочном формате .lgp 8.3 (каждая запись начинается с новой строки).
 */
public class SessionErrorProbeTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // Пользователи: 1 — пустой, 4 — Админ; компьютеры: 1 — ASUS-TUF, 2 — OTHER-PC;
    // приложения: 1 — толстый клиент, 2 — тонкий, 3 — фоновое задание;
    // события: 1 — начало сеанса, 2 — ошибка выполнения, 3 — изменение данных.
    private static final String LGF = ""
        + "1CV8LOG(ver 2.0)\r\n2a6a5fde-0000-0000-0000-000000000000\r\n\r\n"
        + "{1,00000000-0000-0000-0000-000000000000,\"\",1},\r\n"
        + "{1,11111111-1111-1111-1111-111111111111,\"Админ\",4},\r\n"
        + "{2,\"ASUS-TUF\",1},\r\n"
        + "{2,\"OTHER-PC\",2},\r\n"
        + "{3,\"1CV8\",1},\r\n"
        + "{3,\"1CV8C\",2},\r\n"
        + "{3,\"BackgroundJob\",3},\r\n"
        + "{4,\"_$Session$_.Start\",1},\r\n"
        + "{4,\"_$PerformError$_\",2},\r\n"
        + "{4,\"_$Data$_.Update\",3},\r\n"
        + "{6,\"ASUS-TUF\",1},\r\n"
        + "{7,1560,1}\r\n";

    private static final String HEADER = "1CV8LOG(ver 2.0)\r\n2a6a5fde-0000-0000-0000-000000000000\r\n\r\n";

    private static final int START = 1;
    private static final int ERROR = 2;
    private static final int UPDATE = 3;

    private Path logDir;

    @Before
    public void setUp() throws Exception {
        logDir = tmp.newFolder("1Cv8Log").toPath();
        Files.writeString(logDir.resolve("1Cv8.lgf"), LGF, StandardCharsets.UTF_8);
    }

    /** Запись в формате .lgp 8.3: многострочная, с запятой-разделителем перед следующей. */
    private static String rec(long date, int user, int computer, int app, int event, String sev,
                              String comment, long session) {
        return "{" + date + ",N,\r\n{0,0}," + user + "," + computer + "," + app + ",123," + event + ","
            + sev + ",\"" + comment.replace("\"", "\"\"") + "\",0,\r\n{\"U\"},\"\",1,1,0," + session
            + ",0,\r\n{0}\r\n}";
    }

    private static String start(int user, int computer, int app, long session) {
        return rec(20261012100000L, user, computer, app, START, "I", "", session);
    }

    private static String error(String comment, long session) {
        return rec(20261012100500L, 4, 1, 1, ERROR, "E", comment, session);
    }

    private void writePartition(String name, String... records) throws Exception {
        Files.writeString(logDir.resolve(name), HEADER + String.join(",\r\n", records),
            StandardCharsets.UTF_8);
    }

    private void append(String name, String... records) throws Exception {
        Files.writeString(logDir.resolve(name), ",\r\n" + String.join(",\r\n", records),
            StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private SessionErrorProbe probe(String user) throws Exception {
        return new SessionErrorProbe(logDir, user, Set.of("asus-tuf"));
    }

    @Test
    public void errorInSessionStartedAfterLaunch_isReported() throws Exception {
        writePartition("20261012000000.lgp", start(4, 1, 1, 3), rec(20261012090000L, 4, 1, 1, UPDATE, "I", "", 3));
        SessionErrorProbe probe = probe("Админ");

        append("20261012000000.lgp", start(4, 1, 1, 5),
            error("{ОбщийМодуль.ДемоТест_Т.Модуль(12,5)}: Неопознанный оператор", 5));

        String found = probe.unhandledError();
        assertNotNull("ошибка сеанса прогона должна найтись", found);
        assertTrue(found, found.contains("Неопознанный оператор"));
        assertTrue("номер сеанса — чтобы искать дальше в журнале: " + found, found.contains("5"));
    }

    /** Тонкий клиент тоже наш: раннер может открыть его, если так настроена база. */
    @Test
    public void thinClientSession_isOurs() throws Exception {
        writePartition("20261012000000.lgp", start(4, 1, 1, 3));
        SessionErrorProbe probe = probe(null);

        append("20261012000000.lgp", start(1, 1, 2, 9), error("Переменная не определена", 9));

        assertNotNull(probe.unhandledError());
    }

    /** То, что было в журнале до запуска, к прогону отношения не имеет. */
    @Test
    public void errorsBeforeLaunch_andInOlderSessions_ignored() throws Exception {
        writePartition("20261012000000.lgp", start(4, 1, 1, 3), error("старая ошибка", 3));
        SessionErrorProbe probe = probe("Админ");
        assertNull("новых записей нет", probe.unhandledError());

        append("20261012000000.lgp", error("ошибка в сеансе, начатом до запуска", 3));
        assertNull("сеанс начат до запуска — не наш", probe.unhandledError());
    }

    @Test
    public void foreignSessions_ignored() throws Exception {
        writePartition("20261012000000.lgp", start(4, 1, 1, 3));
        SessionErrorProbe probe = probe("Админ");

        append("20261012000000.lgp",
            start(4, 2, 1, 6), error("чужой компьютер", 6),
            start(4, 1, 3, 7), error("фоновое задание", 7),
            start(1, 1, 1, 8), error("другой пользователь", 8));

        assertNull(probe.unhandledError());
    }

    /** Партиция сменилась во время прогона — новая читается целиком. */
    @Test
    public void partitionCreatedAfterLaunch_isRead() throws Exception {
        writePartition("20261012000000.lgp", start(4, 1, 1, 3));
        SessionErrorProbe probe = probe("Админ");

        writePartition("20261012110000.lgp", start(4, 1, 1, 5), error("Неопознанный оператор", 5));

        assertNotNull(probe.unhandledError());
    }

    /** В момент запуска хвост активной партиции мог быть недописан — чтение с середины записи. */
    @Test
    public void launchInTheMiddleOfRecord_handled() throws Exception {
        String half = start(4, 1, 1, 3);
        writePartition("20261012000000.lgp", start(4, 1, 1, 2), half.substring(0, half.length() / 2));
        SessionErrorProbe probe = probe("Админ");

        Files.writeString(logDir.resolve("20261012000000.lgp"), half.substring(half.length() / 2),
            StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        append("20261012000000.lgp", start(4, 1, 1, 5), error("Неопознанный оператор", 5));

        String found = probe.unhandledError();
        assertNotNull(found);
        assertTrue(found, found.contains("Неопознанный оператор"));
    }

    /** Недописанная последняя запись — не ошибка чтения: ждём следующего опроса. */
    @Test
    public void tornTailAfterLaunch_returnsNullWithoutThrowing() throws Exception {
        writePartition("20261012000000.lgp", start(4, 1, 1, 2));
        SessionErrorProbe probe = probe("Админ");

        String next = start(4, 1, 1, 5);
        Files.writeString(logDir.resolve("20261012000000.lgp"), ",\r\n" + next.substring(0, 20),
            StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertNull(probe.unhandledError());
    }

    @Test
    public void forInfobase_noEventLog_returnsNull() throws Exception {
        Path ib = tmp.newFolder("ib").toPath();
        FileConnectionString fcs = mock(FileConnectionString.class);
        when(fcs.getFile()).thenReturn(ib.toString());
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getInfobaseType()).thenReturn(InfobaseType.FILE);
        when(ref.getConnectionString()).thenReturn(fcs);

        assertNull("нет журнала — нет детектора, прогон идёт как раньше",
            SessionErrorProbe.forInfobase(ref, "Админ"));
    }

    @Test
    public void forInfobase_fileInfobaseWithLog_returnsProbe() throws Exception {
        Path ib = tmp.newFolder("ib2").toPath();
        Files.createDirectories(ib.resolve("1Cv8Log"));
        Files.writeString(ib.resolve("1Cv8Log").resolve("1Cv8.lgf"), LGF, StandardCharsets.UTF_8);
        FileConnectionString fcs = mock(FileConnectionString.class);
        when(fcs.getFile()).thenReturn(ib.toString());
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getInfobaseType()).thenReturn(InfobaseType.FILE);
        when(ref.getConnectionString()).thenReturn(fcs);

        assertNotNull(SessionErrorProbe.forInfobase(ref, null));
    }
}
