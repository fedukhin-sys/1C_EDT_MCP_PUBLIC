package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.dt.cli.api.CliApiException;
import com._1c.g5.v8.dt.cli.api.workspace.IExportConfigurationFilesApi;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;

/**
 * Сборка внешнего объекта в {@code .epf}/{@code .erf} в два шага:
 *
 * <ol>
 *   <li><b>экспорт</b> проекта EDT в XML конфигуратора — внутренним сервисом
 *       {@link IExportConfigurationFilesApi} (тот же механизм, что за
 *       {@code 1cedtcli -command export}), без спавна внешнего CLI;</li>
 *   <li><b>упаковка</b> — {@code 1cv8.exe DESIGNER <ИБ>
 *       /LoadExternalDataProcessorOrReportFromFiles <корневой .xml> <выходной файл>}.</li>
 * </ol>
 *
 * <p>Проверка синтаксиса BSL здесь не делается ни на одном шаге — за это отвечает
 * {@link BuildPrecheck}, вызываемый до {@code build}.
 *
 * <p>Сеймы ({@link ProjectExporter}, {@link RuntimeCli.ExecutableResolver},
 * {@link ProcessFactory}, {@code exportDirFactory}) позволяют тестам прогонять оркестрацию
 * без EDT и без запуска 1cv8.
 */
public class ExternalObjectBuilder {

    /** Экспорт проекта рабочей области в XML конфигуратора. */
    public interface ProjectExporter {
        void export(String projectName, Path targetDir) throws ToolException;
    }

    /** Запуск процесса. Собственный, а не {@code RuntimeCli.DefaultProcessFactory} — тот package-private. */
    public interface ProcessFactory {
        Process start(List<String> command, File workingDir) throws IOException;
    }

    /**
     * 1cv8 умеет вернуть 0, не сделав ничего (класс BUG-18). Успех быстрее этого порога —
     * повод предупредить, даже если файл на месте (мог остаться от прошлой сборки).
     */
    private static final long SUSPICIOUS_BUILD_MS = 3_000L;

    /** Сколько символов лога DESIGNER подмешивать в текст ошибки. */
    private static final int LOG_TAIL_CHARS = 4_000;

    /**
     * @param outFile     куда положить .epf/.erf
     * @param infobaseArg строка подключения в форме 1cv8: {@code /F<путь>} или {@code /S<сервер>\<база>}
     */
    public record BuildRequest(String projectName, String objectName, Path outFile,
                               String infobaseArg, String platformVersion, int timeoutSeconds) { }

    public record BuildOutcome(Path epfPath, long durationMs, List<String> warnings) { }

    private final ProjectExporter               exporter;
    private final RuntimeCli.ExecutableResolver executableResolver;
    private final ProcessFactory                processFactory;
    private final ExportDirFactory              exportDirFactory;

    /** Создатель временного каталога под XML-выгрузку. */
    public interface ExportDirFactory {
        Path create() throws IOException;
    }

    @Inject
    public ExternalObjectBuilder(IExportConfigurationFilesApi exportApi,
                                 RuntimeCli.ExecutableResolver executableResolver) {
        this(new CliApiExporter(exportApi), executableResolver, new DefaultProcessFactory(),
             () -> Files.createTempDirectory("edt-mcp-epf-"));
    }

    /** Test seam. */
    public ExternalObjectBuilder(ProjectExporter exporter,
                                 RuntimeCli.ExecutableResolver executableResolver,
                                 ProcessFactory processFactory,
                                 ExportDirFactory exportDirFactory) {
        this.exporter           = exporter;
        this.executableResolver = executableResolver;
        this.processFactory     = processFactory;
        this.exportDirFactory   = exportDirFactory;
    }

    public BuildOutcome build(BuildRequest req) throws ToolException {
        File executable = executableResolver.resolve(req.platformVersion());

        Path exportDir;
        try {
            exportDir = exportDirFactory.create();
        } catch (IOException e) {
            throw new ToolException("не удалось создать временный каталог выгрузки: " + e.getMessage(), e);
        }
        try {
            exporter.export(req.projectName(), exportDir);
            Path rootXml = locateRootXml(exportDir, req.objectName());
            return pack(executable, rootXml, req);
        } finally {
            deleteRecursively(exportDir);
        }
    }

    /** Прогон 1cv8 DESIGNER: запуск, таймаут, трактовка результата. */
    private BuildOutcome pack(File executable, Path rootXml, BuildRequest req) throws ToolException {
        Path log = rootXml.getParent().resolve("designer.log");
        List<String> cmd = buildCommand(executable.getAbsolutePath(), req.infobaseArg(),
                rootXml, req.outFile(), log);

        // Выходной файл мог остаться от прошлой сборки: без удаления «1cv8 отработал вхолостую»
        // выглядит как успех.
        try { Files.deleteIfExists(req.outFile()); }
        catch (IOException e) { throw new ToolException("не удалось перезаписать " + req.outFile()
                + ": " + e.getMessage(), e); }

        long t0 = System.currentTimeMillis();
        Process p;
        try {
            p = processFactory.start(cmd, null);
        } catch (IOException e) {
            throw new ToolException("не удалось запустить 1cv8.exe: " + e.getMessage(), e);
        }
        boolean finished;
        try {
            finished = p.waitFor(req.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ToolException("сборка .epf прервана");
        }
        if (!finished) {
            p.destroyForcibly();
            throw new ToolException("1cv8 DESIGNER timeout after " + req.timeoutSeconds()
                + "s; служебная ИБ могла быть занята другим сеансом" + logTail(log));
        }
        long durationMs = System.currentTimeMillis() - t0;

        int code = p.exitValue();
        if (code != 0) {
            throw new ToolException("1cv8 DESIGNER exit=" + code + logTail(log));
        }
        // exit=0 у 1cv8 ничего не гарантирует — верим только файлу на диске.
        if (!Files.isRegularFile(req.outFile())) {
            throw new ToolException("1cv8 DESIGNER exit=0, но файл " + req.outFile()
                + " не создан" + logTail(log));
        }
        long size;
        try { size = Files.size(req.outFile()); }
        catch (IOException e) { throw new ToolException("не удалось прочитать " + req.outFile()
                + ": " + e.getMessage(), e); }
        if (size == 0) {
            throw new ToolException("1cv8 DESIGNER exit=0, но файл " + req.outFile()
                + " пуст" + logTail(log));
        }

        List<String> warnings = new ArrayList<>();
        if (durationMs < SUSPICIOUS_BUILD_MS) {
            warnings.add("1cv8 DESIGNER отработал за " + durationMs
                + " мс — подозрительно быстро для реальной сборки (класс BUG-18): "
                + "проверьте, что .epf действительно пересобран");
        }
        return new BuildOutcome(req.outFile(), durationMs, warnings);
    }

    /**
     * {@code 1cv8.exe DESIGNER /F<ИБ> /LoadExternalDataProcessorOrReportFromFiles <xml> <epf>}.
     *
     * <p>Кавычки вокруг аргументов не ставим: {@link ProcessBuilder} на Windows экранирует
     * пробелы сам, а ручные кавычки уехали бы в 1cv8 буквально (баг {@code RuntimeCli.createFileInfobase}).
     *
     * <p>{@code /Out} обязателен: при ошибке 1cv8 не пишет в stderr ничего — без лога
     * диагностики не будет вовсе.
     */
    static List<String> buildCommand(String executable, String infobaseArg,
                                     Path rootXml, Path outFile, Path log) {
        return List.of(
            executable,
            "DESIGNER",
            infobaseArg,
            "/DisableStartupDialogs",
            "/DisableStartupMessages",
            "/Out", log.toString(),
            "/LoadExternalDataProcessorOrReportFromFiles", rootXml.toString(), outFile.toString());
    }

    /**
     * Ищет корневой {@code .xml} выгруженного объекта: EDT кладёт в каталог выгрузки
     * {@code <Имя>.xml} рядом с одноимённым подкаталогом. Если имя не совпало, но верхнеуровневый
     * {@code .xml} ровно один — берём его.
     */
    static Path locateRootXml(Path exportDir, String objectName) throws ToolException {
        Path byName = exportDir.resolve(objectName + ".xml");
        if (Files.isRegularFile(byName)) return byName;

        // Раскладку выгрузки задаёт сам EDT, и она не обязана быть плоской: live-smoke
        // 2026-07-17 показал, что на верхнем уровне .xml может не быть вовсе. Поэтому ищем
        // <Имя>.xml на любой глубине, начиная с самых верхних — и лишь потом сдаёмся.
        List<Path> byNameDeep;
        List<Path> anyXml;
        try (Stream<Path> walk = Files.walk(exportDir)) {
            List<Path> allXml = walk.filter(Files::isRegularFile)
                                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                                    .sorted(java.util.Comparator.comparingInt(Path::getNameCount)
                                                                .thenComparing(Path::toString))
                                    .toList();
            byNameDeep = allXml.stream()
                               .filter(p -> p.getFileName().toString().equals(objectName + ".xml"))
                               .toList();
            anyXml = allXml;
        } catch (IOException e) {
            throw new ToolException("не удалось прочитать каталог выгрузки " + exportDir
                + ": " + e.getMessage(), e);
        }
        if (!byNameDeep.isEmpty()) return byNameDeep.get(0);
        if (anyXml.size() == 1)    return anyXml.get(0);

        // Каталог временный и будет удалён, поэтому единственный шанс понять, что выгрузил EDT, —
        // перечислить содержимое прямо в сообщении.
        throw new ToolException("в выгрузке " + exportDir + " не найден '" + objectName
            + ".xml'; найденные .xml: " + anyXml + "; содержимое каталога: " + describe(exportDir));
    }

    /** Плоский список содержимого каталога — для диагностики неожиданной раскладки выгрузки. */
    private static String describe(Path dir) {
        try (Stream<Path> walk = Files.walk(dir, 3)) {
            List<String> entries = walk.filter(p -> !p.equals(dir))
                                       .map(p -> dir.relativize(p).toString()
                                                 + (Files.isDirectory(p) ? "/" : ""))
                                       .sorted()
                                       .limit(40)
                                       .toList();
            return entries.isEmpty() ? "<пусто>" : entries.toString();
        } catch (IOException e) {
            return "<не прочитано: " + e.getMessage() + ">";
        }
    }

    /** Хвост лога DESIGNER — единственный источник диагностики при провале. */
    private static String logTail(Path log) {
        if (!Files.isRegularFile(log)) return "; лог 1cv8 не создан";
        String text;
        try {
            // 1cv8 пишет /Out в кодировке ОС.
            text = Files.readString(log, Charset.defaultCharset()).trim();
        } catch (IOException | RuntimeException e) {
            return "; лог 1cv8 не прочитан: " + e;
        }
        if (text.isEmpty()) return "; лог 1cv8 пуст";
        if (text.length() > LOG_TAIL_CHARS) text = text.substring(text.length() - LOG_TAIL_CHARS);
        return "; лог 1cv8: " + text;
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best-effort */ }
            });
        } catch (IOException | UncheckedIOException ignored) { /* best-effort */ }
    }

    /** Production-экспортёр: внутренний сервис EDT, тот же, что за {@code 1cedtcli export}. */
    static final class CliApiExporter implements ProjectExporter {
        private final IExportConfigurationFilesApi api;
        CliApiExporter(IExportConfigurationFilesApi api) { this.api = api; }

        @Override public void export(String projectName, Path targetDir) throws ToolException {
            try {
                api.exportProject(projectName, targetDir);
            } catch (CliApiException e) {
                throw new ToolException("экспорт проекта '" + projectName + "' в XML не удался: "
                    + e.getMessage(), e);
            }
        }
    }

    static final class DefaultProcessFactory implements ProcessFactory {
        @Override public Process start(List<String> command, File workingDir) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) pb.directory(workingDir);
            return pb.start();
        }
    }
}
