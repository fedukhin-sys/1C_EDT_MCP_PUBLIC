package ru.fedukhin.edt.mcp.tools.testrun.internal;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogLocator;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogReferences;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventRecord;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.LgfParser;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.LgpParser;

/**
 * Ищет в журнале регистрации базы необработанную ошибку сеанса, открытого прогоном тестов.
 *
 * <p>Зачем: модуль, который EDT считает правильным, может не компилироваться в рантайме
 * (тест-модуль с флагом клиента и серверным перечислением, {@code Новый Запрос(Т).Выполнить()}).
 * Клиент тогда показывает окно ошибки, обработчик раннера не выполняется, а клиент стоит на
 * рабочем столе — и {@code run_test_method} ждал до таймаута (10.10 и 12.10.2026: 900 и 1500 с).
 * Платформа пишет такую ошибку в журнал событием {@code _$PerformError$_} с модулем и строкой.
 *
 * <p>Свой сеанс узнаём по {@code _$Session$_.Start} после запуска: клиентское приложение
 * ({@code 1CV8}/{@code 1CV8C}), этот компьютер, пользователь прогона (если задан). Ошибки чужих
 * сеансов и сеансов, начатых раньше, не учитываются.
 *
 * <p>Читается только дописанное после создания: размер последней партиции запоминается в
 * конструкторе, чтение начинается с первой записи после этого места (запись .lgp всегда
 * начинается с новой строки: {@code {ГГГГММДДччммсс,}). Партиции, появившиеся позже, читаются
 * целиком. Формат SQLite ({@code .lgd}) не поддержан — тогда детектора нет.
 */
public final class SessionErrorProbe implements TestRunnerLauncher.FailureProbe {

    static final String SESSION_START = "_$Session$_.Start";
    static final String PERFORM_ERROR = "_$PerformError$_";

    /** Толстый и тонкий клиент — прогон запускает {@code 1cv8 ENTERPRISE}. */
    private static final Set<String> CLIENT_APPLICATIONS = Set.of("1CV8", "1CV8C");

    /** Предел чтения за один опрос: дописанное за прогон — килобайты, это защита от аномалий. */
    private static final int MAX_READ_BYTES = 64 * 1024 * 1024;

    /** Предел длины текста ошибки в ответе. */
    private static final int MAX_COMMENT_CHARS = 2000;

    private final Path logDir;
    private final String user;
    private final Set<String> computers;
    /** Партиция → её размер в момент запуска; партиции, которых тогда не было, читаются целиком. */
    private final Map<String, Long> startSizes = new HashMap<>();
    private final String lastPartitionAtStart;

    /**
     * @param user      пользователь ИБ прогона или {@code null} (OS-аутентификация — имя неизвестно)
     * @param computers имена этого компьютера в нижнем регистре; пусто — не фильтровать
     */
    public SessionErrorProbe(Path logDir, String user, Set<String> computers) throws IOException {
        this.logDir = logDir;
        this.user = user == null || user.isBlank() ? null : user;
        this.computers = Set.copyOf(computers);
        String last = null;
        for (Path part : partitions()) {
            String name = part.getFileName().toString();
            startSizes.put(name, Files.size(part));
            last = name;
        }
        this.lastPartitionAtStart = last;
    }

    /**
     * Детектор для базы прогона или {@code null}, если журнал не найти (не та версия формата,
     * нет доступа к каталогу кластера) — прогон тогда идёт без него.
     */
    public static TestRunnerLauncher.FailureProbe forInfobase(InfobaseReference ref, String user) {
        try {
            EventLogLocator.ResolvedLog log = new EventLogLocator().locate(ref);
            if (!log.exists || !Files.isRegularFile(log.logDir.resolve("1Cv8.lgf"))) return null;
            return new SessionErrorProbe(log.logDir, user, localComputerNames());
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    @Override
    public String unhandledError() throws IOException {
        Path lgf = logDir.resolve("1Cv8.lgf");
        if (!Files.isRegularFile(lgf)) return null;
        // Справочники перечитываются каждый раз: новый сеанс добавляет в них компьютер,
        // пользователя или событие, которых до запуска не было.
        EventLogReferences refs = new LgfParser().parse(lgf);
        LgpParser parser = new LgpParser(refs);
        Set<Long> ours = new HashSet<>();
        String[] found = { null };
        for (Path part : partitions()) {
            String name = part.getFileName().toString();
            if (lastPartitionAtStart != null && name.compareTo(lastPartitionAtStart) < 0) continue;
            String text = readAppended(part, startSizes.getOrDefault(name, 0L));
            if (text == null) continue;
            try {
                parser.stream(new BufferedReader(new StringReader(text)), null, rec -> {
                    if (SESSION_START.equals(rec.event) && isOurs(rec)) {
                        ours.add(rec.session);
                    } else if (PERFORM_ERROR.equals(rec.event) && ours.contains(rec.session)) {
                        found[0] = describe(rec);
                        return false;
                    }
                    return true;
                });
            } catch (IOException e) {
                // Единственная новая запись ещё дописывается — прочтём на следующем опросе.
                continue;
            }
            if (found[0] != null) return found[0];
        }
        return null;
    }

    private boolean isOurs(EventRecord rec) {
        if (rec.application == null || !CLIENT_APPLICATIONS.contains(rec.application)) return false;
        if (!computers.isEmpty()
            && (rec.computer == null || !computers.contains(rec.computer.toLowerCase(Locale.ROOT)))) {
            return false;
        }
        return user == null || user.equalsIgnoreCase(rec.user);
    }

    private static String describe(EventRecord rec) {
        String comment = rec.comment == null ? "" : rec.comment.trim();
        if (comment.length() > MAX_COMMENT_CHARS) comment = comment.substring(0, MAX_COMMENT_CHARS) + "…";
        return "сеанс " + rec.session + " (" + rec.dateIso + "): " + comment;
    }

    /** Партиции журнала по возрастанию имени (оно же — время начала партиции). */
    private List<Path> partitions() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(logDir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lgp"))
                 .sorted()
                 .forEach(out::add);
        }
        return out;
    }

    /**
     * Текст партиции после {@code fromOffset}, начиная с первой целой записи, или {@code null},
     * если новых записей нет. С нуля — файл целиком: заголовок пропустит {@link LgpParser}.
     */
    static String readAppended(Path part, long fromOffset) throws IOException {
        long size = Files.size(part);
        if (size <= fromOffset) return null;
        // Байт перед смещением нужен, чтобы узнать запись, начатую ровно на нём.
        long start = fromOffset > 0 ? fromOffset - 1 : 0;
        int length = (int) Math.min(size - start, MAX_READ_BYTES);
        byte[] bytes = new byte[length];
        try (InputStream in = Files.newInputStream(part)) {
            in.skipNBytes(start);
            int read = in.readNBytes(bytes, 0, length);
            if (read < length) bytes = java.util.Arrays.copyOf(bytes, read);
        }
        if (fromOffset == 0) return new String(bytes, StandardCharsets.UTF_8);
        int recordStart = firstRecordStart(bytes);
        if (recordStart < 0) return null;
        return new String(bytes, recordStart, bytes.length - recordStart, StandardCharsets.UTF_8);
    }

    /**
     * Позиция первой записи: {@code {} + 14 цифр даты + запятая сразу после перевода строки.
     * Вложенные списки записи ({@code {0,0}}, {@code {"U"}}) так не начинаются. Поиск по байтам
     * безопасен для UTF-8: все искомые символы — ASCII.
     */
    static int firstRecordStart(byte[] bytes) {
        for (int i = 0; i + 16 < bytes.length; i++) {
            if (bytes[i] != '\n' || bytes[i + 1] != '{') continue;
            boolean digits = true;
            for (int k = 2; k < 16 && digits; k++) {
                digits = bytes[i + k] >= '0' && bytes[i + k] <= '9';
            }
            if (digits && bytes[i + 16] == ',') return i + 1;
        }
        return -1;
    }

    /** Имена этого компьютера, под которыми 1С пишет его в журнал, в нижнем регистре. */
    static Set<String> localComputerNames() {
        Set<String> names = new HashSet<>();
        String env = System.getenv("COMPUTERNAME");
        if (env != null && !env.isBlank()) names.add(env.trim().toLowerCase(Locale.ROOT));
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                String shortName = host.contains(".") ? host.substring(0, host.indexOf('.')) : host;
                names.add(shortName.toLowerCase(Locale.ROOT));
            }
        } catch (IOException | RuntimeException ignored) {
            // Имя по сети не узнать — остаётся переменная окружения.
        }
        return names;
    }
}
