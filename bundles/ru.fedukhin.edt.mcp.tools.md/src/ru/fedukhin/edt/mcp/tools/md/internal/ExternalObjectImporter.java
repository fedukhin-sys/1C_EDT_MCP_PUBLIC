package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.import_.IImportOperation;
import com._1c.g5.v8.dt.import_.IImportOperationFactory;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectRestorer;
import com._1c.g5.v8.dt.platform.version.Version;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Импорт готового {@code .epf}/{@code .erf} в проект внешних объектов — тем же путём, которым
 * идёт мастер «Импорт → Внешние отчёты и обработки» в IDE:
 *
 * <pre>
 * IExternalObjectRestorer.restore(project, файл, tmp, monitor)   // .epf → Designer-XML
 * IImportOperationFactory.createImportExternalObjectOperation(...)  // XML → исходники EDT
 * </pre>
 *
 * <p>Распаковка — это запуск платформы: ИБ, учётку и версию сервис берёт из приложения,
 * ассоциированного с проектом (как {@link ExternalObjectBuilder} при сборке), поэтому здесь нет
 * ни строки подключения, ни резолва {@code 1cv8.exe}.
 *
 * <p>Платформа кладёт рядом два объекта: {@code <корень>.xml} с описанием обработки и каталог
 * {@code <корень>/} с {@code Ext/}, {@code Forms/}, {@code Templates/}. Корень — это
 * {@code tmp/<имя файла без расширения>}, ровно как считает мастер; фабрика импорта получает
 * путь без расширения. Имя объекта берётся из {@code <Name>} корневого XML, а не из имени
 * файла: {@code АРМ_150626.epf} даёт объект {@code АРМ}.
 *
 * <p>Сеймы ({@link ContextResolver}, оба сервиса EDT) позволяют тестам прогонять оркестрацию
 * без EDT и без запуска платформы.
 */
public class ExternalObjectImporter {

    /** kind корневого тега → каталог в {@code src/}. */
    private static final Map<String, String> KIND_FOLDER = Map.of(
            "ExternalDataProcessor", "ExternalDataProcessors",
            "ExternalReport",        "ExternalReports");

    /** Головы XML хватает: {@code <Properties><Name>} идёт первым блоком описания объекта. */
    private static final int DESCRIPTOR_HEAD_BYTES = 64 * 1024;

    private static final Pattern KIND_TAG  = Pattern.compile("<(ExternalDataProcessor|ExternalReport)[\\s>]");
    private static final Pattern NAME_TAG  = Pattern.compile("<Name>([^<]+)</Name>");

    /** Пауза перед повтором после транзиентного «already connected». */
    private static final long ALREADY_CONNECTED_RETRY_DELAY_MS = 1_000L;

    /** Версия платформы и базовый проект конфигурации — всё, что импорту нужно от проекта. */
    public record ProjectContext(Version version, IConfigurationProject baseProject) { }

    /** Резолв контекста проекта. Он же отбивает проекты не того типа. */
    public interface ContextResolver {
        ProjectContext resolve(IProject project) throws ToolException;
    }

    public record ImportRequest(IProject project, Path file, boolean overwrite, int timeoutSeconds) { }

    public record ImportOutcome(String fqn, String objectDir, long durationMs, List<String> warnings) { }

    /** Что удалось вычитать из корневого XML распакованной обработки. */
    private record Descriptor(String kind, String name) {
        String fqn()       { return kind + "." + name; }
        String objectDir() { return "src/" + KIND_FOLDER.get(kind) + "/" + name; }
        String mdoPath()   { return objectDir() + "/" + name + ".mdo"; }
    }

    private final IExternalObjectRestorer restorer;
    private final IImportOperationFactory factory;
    private final ContextResolver         contexts;

    @Inject
    public ExternalObjectImporter(IExternalObjectRestorer restorer, IImportOperationFactory factory,
                                  IV8ProjectManager projectManager) {
        this(restorer, factory, new V8ContextResolver(projectManager));
    }

    /** Test seam. */
    public ExternalObjectImporter(IExternalObjectRestorer restorer, IImportOperationFactory factory,
                                  ContextResolver contexts) {
        this.restorer = restorer;
        this.factory  = factory;
        this.contexts = contexts;
    }

    public ImportOutcome importObject(ImportRequest req) throws ToolException {
        ProjectContext context = contexts.resolve(req.project());

        Path tmpDir;
        try { tmpDir = Files.createTempDirectory("edt-mcp-xml-ext-obj-"); }
        catch (IOException e) {
            throw new ToolException("не удалось создать временный каталог для распаковки: "
                + e.getMessage(), e);
        }

        long t0 = System.currentTimeMillis();
        try {
            restore(req, tmpDir);

            Path root       = tmpDir.resolve(trimExtension(req.file().getFileName().toString()));
            Descriptor desc = readDescriptor(locateRootXml(tmpDir, root));

            // Имя известно только после распаковки, поэтому и коллизия проверяется здесь —
            // но до импорта: EDT перезаписал бы исходники молча (в IDE на этом месте диалог).
            if (!req.overwrite() && req.project().getFolder(desc.objectDir()).exists()) {
                throw new ToolException("в проекте '" + req.project().getName() + "' уже есть "
                    + desc.fqn() + " (" + desc.objectDir() + "); передайте overwrite=true, чтобы "
                    + "заменить его импортируемым");
            }

            List<String> warnings = runImport(req, context, root, desc);

            // Возврат без исключения ничего не гарантирует — верим файлу на диске.
            refreshQuietly(req.project());
            if (!req.project().getFile(desc.mdoPath()).exists()) {
                throw new ToolException("EDT завершил импорт без ошибки, но исходники не появились: "
                    + "нет " + desc.mdoPath());
            }

            return new ImportOutcome(desc.fqn(), desc.objectDir(),
                System.currentTimeMillis() - t0, List.copyOf(warnings));
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    // --- шаги -------------------------------------------------------------

    /**
     * Распаковка {@code .epf} в Designer-XML с одним повтором при транзиентном
     * «already connected»: первое обращение к ИБ проекта в свежем сеансе EDT падает именно так
     * (тот же класс отказа, что при сборке — live-smoke 2026-07-17).
     */
    private void restore(ImportRequest req, Path tmpDir) throws ToolException {
        Throwable cause = runWithTimeout("распаковка .epf", req.timeoutSeconds(),
            monitor -> restorer.restore(req.project(), req.file(), tmpDir, monitor));
        if (cause == null) return;
        if (isAlreadyConnected(cause)) {
            sleepQuietly(ALREADY_CONNECTED_RETRY_DELAY_MS);
            cause = runWithTimeout("распаковка .epf", req.timeoutSeconds(),
                monitor -> restorer.restore(req.project(), req.file(), tmpDir, monitor));
            if (cause == null) return;
        }
        throw toToolException("распаковать внешний объект", cause);
    }

    /** Импорт распакованного XML в проект. Возвращает предупреждения из статуса операции. */
    private List<String> runImport(ImportRequest req, ProjectContext context, Path root,
                                   Descriptor desc) throws ToolException {
        IImportOperation operation = factory.createImportExternalObjectOperation(
                req.project().getName(), context.version(), root, context.baseProject());
        operation.setRefreshProject(true);

        Throwable cause = runWithTimeout("импорт " + desc.fqn(), req.timeoutSeconds(),
            operation::run);
        if (cause != null) throw toToolException("импортировать внешний объект", cause);

        IStatus status = operation.getStatus();
        if (status == null || status.isOK()) return new ArrayList<>();
        if (status.getSeverity() == IStatus.ERROR || status.getSeverity() == IStatus.CANCEL) {
            throw new ToolException("EDT не смог импортировать внешний объект: " + describe(status));
        }
        List<String> warnings = new ArrayList<>();
        warnings.add(describe(status));
        return warnings;
    }

    // --- корневой XML -----------------------------------------------------

    /**
     * Корень выгрузки — {@code tmp/<имя файла без расширения>}, рядом с ним платформа кладёт
     * {@code <корень>.xml}. Если имя разошлось (файл переименовывали), берём единственный
     * XML верхнего уровня: имя объекта всё равно живёт внутри, а не в имени файла.
     */
    private static Path locateRootXml(Path tmpDir, Path root) throws ToolException {
        Path expected = root.resolveSibling(root.getFileName() + ".xml");
        if (Files.isRegularFile(expected)) return expected;

        List<Path> xmls;
        try (Stream<Path> entries = Files.list(tmpDir)) {
            xmls = entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new ToolException("не удалось прочитать результат распаковки в " + tmpDir + ": "
                + e.getMessage(), e);
        }
        if (xmls.size() == 1) return xmls.get(0);
        if (xmls.isEmpty()) {
            throw new ToolException("распаковка не дала корневого XML в " + tmpDir
                + " — файл не похож на внешнюю обработку или отчёт");
        }
        throw new ToolException("в " + tmpDir + " несколько корневых XML " + xmls
            + " — не понятно, какой объект импортировать");
    }

    /** Вид и имя объекта из корневого XML: {@code <ExternalDataProcessor>} … {@code <Name>}. */
    private static Descriptor readDescriptor(Path rootXml) throws ToolException {
        String head;
        try {
            byte[] bytes = readHead(rootXml, DESCRIPTOR_HEAD_BYTES);
            head = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("не удалось прочитать " + rootXml + ": " + e.getMessage(), e);
        }

        Matcher kind = KIND_TAG.matcher(head);
        if (!kind.find()) {
            throw new ToolException("в " + rootXml + " нет корневого тега ExternalDataProcessor "
                + "или ExternalReport — импортировать можно только внешние обработки и отчёты");
        }
        int propertiesAt = head.indexOf("<Properties>", kind.end());
        Matcher name = NAME_TAG.matcher(head);
        if (!name.find(propertiesAt >= 0 ? propertiesAt : kind.end())) {
            throw new ToolException("в " + rootXml + " не найдено имя объекта (<Name>)");
        }
        return new Descriptor(kind.group(1), name.group(1));
    }

    private static byte[] readHead(Path file, int limit) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return in.readNBytes(limit);
        }
    }

    /** {@code АРМ_150626.epf} → {@code АРМ_150626}; хвостовые точки съедаются, как в EDT. */
    static String trimExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String trimmed = dot > 0 ? fileName.substring(0, dot) : fileName;
        return trimmed.replaceAll("\\.+$", "");
    }

    // --- инфраструктура ---------------------------------------------------

    /** Тело шага: всё, что EDT умеет прервать только через отмену монитора. */
    private interface MonitoredStep {
        void run(IProgressMonitor monitor) throws Exception;
    }

    /**
     * Один прогон шага в отдельном потоке. {@code null} при успехе, иначе причина отказа;
     * таймаут и прерывание — не отказ сервиса, они бросаются напрямую.
     *
     * <p>Сервисы синхронные и на занятой ИБ умеют ждать сколько угодно, поэтому по таймауту
     * отменяем {@link IProgressMonitor} — единственный способ попросить EDT остановиться.
     */
    private static Throwable runWithTimeout(String what, int timeoutSeconds, MonitoredStep step)
            throws ToolException {
        IProgressMonitor monitor = new NullProgressMonitor();
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "edt-mcp-epf-import");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<?> task = pool.submit(() -> {
                step.run(monitor);
                return null;
            });
            try {
                task.get(timeoutSeconds, TimeUnit.SECONDS);
                return null;
            } catch (TimeoutException e) {
                monitor.setCanceled(true);
                throw new ToolException(what + ": timeout after " + timeoutSeconds
                    + "s; ИБ проекта могла быть занята другим сеансом", e);
            } catch (InterruptedException e) {
                monitor.setCanceled(true);
                Thread.currentThread().interrupt();
                throw new ToolException(what + " прервана", e);
            } catch (ExecutionException e) {
                return e.getCause();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Транзиентный отказ «ИБ уже подключена» — checkArgument в стратегии синхронизации EDT. */
    private static boolean isAlreadyConnected(Throwable cause) {
        return cause instanceof IllegalArgumentException
            && cause.getMessage() != null
            && cause.getMessage().toLowerCase(Locale.ROOT).contains("already connected");
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Причина отказа сервиса → {@link ToolException} с actionable-подсказкой. Оригинальное
     * сообщение сохраняется всегда: EDT кладёт в него настоящую причину, домысливать её нельзя.
     */
    private static ToolException toToolException(String action, Throwable cause) {
        if (cause instanceof CoreException e) {
            return new ToolException("EDT не смог " + action + ": " + describe(e.getStatus())
                + "; если причина в отсутствии информационной базы у проекта — свяжите проект с ИБ "
                + "(associate_infobase) или создайте её (create_infobase)", e);
        }
        if (isAlreadyConnected(cause)) {
            return new ToolException("ИБ проекта занята (EDT: " + cause.getMessage()
                + "); повтор не помог — закройте сеансы этой ИБ и повторите импорт", cause);
        }
        if (cause instanceof IllegalStateException e) {
            return new ToolException("EDT отклонил импорт: " + e.getMessage()
                + " (типичная причина — проект не является проектом внешних отчётов и обработок)", e);
        }
        if (cause instanceof ToolException e) return e;
        if (cause instanceof RuntimeException e) throw e;
        return new ToolException("не удалось " + action + ": " + cause, cause);
    }

    /** Текст статуса вместе с children: EDT кладёт настоящую причину во вложенные статусы. */
    private static String describe(IStatus status) {
        if (status == null) return "<без статуса>";
        StringBuilder sb = new StringBuilder(String.valueOf(status.getMessage()));
        for (IStatus child : status.getChildren()) {
            sb.append("; ").append(child.getMessage());
        }
        if (status.getException() != null) sb.append("; ").append(status.getException());
        return sb.toString();
    }

    private static void refreshQuietly(IProject project) {
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort: дальше всё равно проверяем файл */ }
    }

    /** Временный каталог убираем всегда — и на успехе, и на любом отказе. */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> entries = Files.walk(dir)) {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });
        } catch (IOException | UncheckedIOException ignored) {
            /* временный каталог в temp — уборка best-effort, отказ импорта из-за неё бессмысленен */
        }
    }

    /**
     * Production-резолвер: проект должен быть external-object и иметь базовый проект
     * конфигурации — версию платформы и родителя берём оттуда, а не из аргументов инструмента.
     */
    static final class V8ContextResolver implements ContextResolver {
        private final IV8ProjectManager projectManager;

        V8ContextResolver(IV8ProjectManager projectManager) { this.projectManager = projectManager; }

        @Override public ProjectContext resolve(IProject project) throws ToolException {
            IV8Project v8Project = projectManager.getProject(project);
            if (!(v8Project instanceof IExternalObjectProject external)) {
                throw new ToolException("проект '" + project.getName()
                    + "' не является проектом внешних отчётов и обработок"
                    + (v8Project == null ? " (EDT не видит его как проект 1С)" : ""));
            }
            IConfigurationProject parent = external.getParent();
            if (parent == null) {
                throw new ToolException("у проекта '" + project.getName()
                    + "' нет базового проекта конфигурации (DT-INF/PROJECT.PMF, Base-Project) — "
                    + "без него EDT не сможет разрешить ссылки импортируемого объекта");
            }
            return new ProjectContext(external.getVersion(), parent);
        }
    }
}
