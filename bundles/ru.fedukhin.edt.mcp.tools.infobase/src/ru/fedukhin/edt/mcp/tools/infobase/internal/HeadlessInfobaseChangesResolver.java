package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseChangesResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSyncResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChangeType;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateInfobaseFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateProjectFlow;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;

/**
 * Безголовый обработчик изменений базы для {@code retrieveInfobaseChanges}: забирает версию базы
 * в проект.
 *
 * <p>Повторяет ветку IDE «импортировать изменения в проект»
 * ({@code InfobaseUpdateDialogBasedCallback.doUpdateProject}), но без диалогов: IDE подменяет
 * ассистент на compare-редактор ({@code CompareEditorMergingConflictResolveAssist}), а здесь
 * остаётся штатный ассистент ядра — в обёртке {@link ReplacingAssist}: сам он версию из базы только
 * копирует поверх проекта, обёртка удаляет то, что удалено в базе, обновляет ресурсы проекта и
 * запоминает, была ли перезагрузка полной ({@code Retrieval.fullReload}).
 *
 * <p>Класс реализует интерфейс с сигнатурой EDT 2026.x и грузится только после проверки
 * {@link SyncV2#requireAvailable()}; в Guice не биндится.
 *
 * <p><b>Почему {@link #retrieve} — единственная точка входа для {@code ProjectFromInfobaseUpdater}.</b>
 * Классы, которые биндятся в Guice, обязаны загружаться и линковаться и на EDT без API v2. Тип
 * грузится не от любого упоминания в байткоде, а когда (1) Guice при создании инжектора читает
 * рефлексией сигнатуры конструкторов, методов и полей или (2) верификатор при линковке ЦЕЛОГО
 * класса проверяет присваиваемость — значение передаётся, возвращается или сохраняется туда, где
 * ожидается тип с ДРУГИМ именем. {@code checkcast}, вызовы методов ({@code invoke*}) и
 * class-литералы ({@code ldc}) резолвят тип лениво, при первом исполнении. Раньше
 * {@code ProjectFromInfobaseUpdater.update} сам создавал этот обработчик и передавал его в параметр
 * {@code IInfobaseChangesResolver} метода {@code retrieveInfobaseChanges} — присваиваемость к типу с
 * другим именем: верификатор загружал интерфейс, и на EDT без API v2 весь
 * {@code ProjectFromInfobaseUpdater} падал {@code NoClassDefFoundError} при загрузке, то есть
 * создание Guice-инжектора (общего для всех инструментов бандла) рушилось целиком, хотя
 * {@code update()} никогда не вызывался. {@link #retrieve} держит ВСЕ обращения к
 * {@code IInfobaseChangesResolver}/{@code InfobaseSyncResolution}/{@code InfobaseChangesResolutionResult}/
 * v2-типам в этом классе, который в Guice не биндится и грузится лениво — при первом исполнении
 * {@code invokestatic} в {@code update()}, то есть только после проверки
 * {@code SyncV2.stateManager() != null}. Сам этот класс на EDT без API v2 не грузится (он реализует
 * {@code IInfobaseChangesResolver}) — на этом держится контрольная проверка
 * {@code ProjectFromInfobaseUpdaterLinkageTest}.
 */
public final class HeadlessInfobaseChangesResolver implements IInfobaseChangesResolver {

    private static final String PLUGIN_ID = "ru.fedukhin.edt.mcp.tools.infobase";

    private final IInfobaseSynchronizationStateManager stateManager;
    private final IV8ProjectManager projects;
    /** Что узнали обработчик и {@link ReplacingAssist} за это обновление; читает {@link #retrieve}. */
    private final Observations observations;

    public HeadlessInfobaseChangesResolver(IInfobaseSynchronizationStateManager stateManager,
                                           IV8ProjectManager projects) {
        this(stateManager, projects, new Observations());
    }

    public HeadlessInfobaseChangesResolver(IInfobaseSynchronizationStateManager stateManager,
                                           IV8ProjectManager projects, Observations observations) {
        this.stateManager = stateManager;
        this.projects = projects;
        this.observations = observations;
    }

    /**
     * Что обработчик и обёртка узнали за одно обновление проекта из базы; {@link #retrieve} переносит это в
     * {@code ProjectFromInfobaseUpdater.Retrieval}. Только типы JDK.
     */
    public static final class Observations {
        private final AtomicBoolean fullReload = new AtomicBoolean();
        private final AtomicBoolean edtChanges = new AtomicBoolean();
        private final Set<String> mergedKeys = ConcurrentHashMap.newKeySet();
        private final AtomicReference<String> mergedKeysFailure = new AtomicReference<>();

        /** Среди слияний была полная перезагрузка (флаг {@code fullReload}, с которым EDT вызвала слияние). */
        public boolean fullReload() {
            return fullReload.get();
        }

        /** EDT передала обработчику изменения на своей стороне ({@code collectEdtObjectChanges}). */
        public boolean edtChanges() {
            return edtChanges.get();
        }

        /**
         * Ключи ресурсов, которые записало или удалило слияние, — в формате EDT: путь от корня проекта
         * через {@code /} (как у {@code IResourceStoreManager.getSignaturesForExternalFiles} и
         * {@code getEffectiveResourceMetadata}; см. {@link ReplacingAssist#fileKeys}).
         */
        public Set<String> mergedKeys() {
            return Set.copyOf(mergedKeys);
        }

        /** Почему ключи записанного слиянием узнать не удалось; {@code null} — удалось (или слияния не было). */
        public String mergedKeysFailure() {
            return mergedKeysFailure.get();
        }
    }

    /**
     * Вызывает {@code retrieveInfobaseChanges} с этим обработчиком и возвращает итог как безопасный
     * record ({@code ProjectFromInfobaseUpdater.Retrieval}) — единственное, что попадает в байткод
     * {@code ProjectFromInfobaseUpdater.update}. {@code stateManager} передаётся как {@code Object}
     * (см. {@link SyncV2#stateManager()}) и приводится к типу v2 уже здесь, внутри метода класса,
     * который не биндится в Guice.
     *
     * <p>{@code Retrieval.fullReload} — флаг, с которым EDT вызвала слияние
     * ({@code IInfobaseConfigurationChange.isFullReloadRequired()}; его записывает {@link ReplacingAssist}).
     * {@code MergingConflictResolver.resolveConflict} вызывает слияние синхронно, до возврата из
     * {@code retrieveInfobaseChanges}, — к этому моменту флаг уже записан. Слияния не было (например,
     * {@code NO_CHANGES}) — {@code false}; вызови будущая EDT слияние позже, флаг тоже останется
     * {@code false}, а это осторожная сторона: проект отмечается совпадающим с базой только по второму
     * условию (см. {@code ProjectFromInfobaseUpdater.update}).
     *
     * @param stateManager результат {@link SyncV2#stateManager()}, заведомо не {@code null} и
     *     заведомо {@code IInfobaseSynchronizationStateManager} — это гарантирует вызывающий код
     */
    public static ProjectFromInfobaseUpdater.Retrieval retrieve(IInfobaseSynchronizationManager sync,
            Object stateManager, IV8ProjectManager projects, IProject project, InfobaseReference infobase,
            IProgressMonitor monitor) throws InfobaseSynchronizationException {
        Observations seen = new Observations();
        HeadlessInfobaseChangesResolver resolver = new HeadlessInfobaseChangesResolver(
            (IInfobaseSynchronizationStateManager) stateManager, projects, seen);
        InfobaseSyncResolution resolution = sync.retrieveInfobaseChanges(project, infobase, resolver, true, monitor);
        InfobaseChangesResolutionResult result = resolution == null ? null
            : resolution.getInfobaseChangesResolutionResult();
        CompletableFuture<IStatus> pending = resolution == null ? null : resolution.getConflictResolution();
        return new ProjectFromInfobaseUpdater.Retrieval(result == null ? null : result.name(), pending,
            seen.fullReload(), seen.edtChanges(), seen.mergedKeys(), seen.mergedKeysFailure());
    }

    /**
     * Три множества — изменения на стороне EDT, как их собрал поток синхронизации
     * ({@code collectEdtObjectChanges}); передаются дальше без изменений. Непустые — для EDT проект не
     * совпадал с базой ещё до обновления: это записывается в {@link Observations#edtChanges()} (L8, fix
     * round 4) — инкрементальное обновление такого проекта не отмечается синхронизированным.
     */
    @Override
    public InfobaseConflictResolution resolveInfobaseChanges(IProject project, InfobaseReference infobase,
            Set<EObject> edtChanged, Set<EObject> edtRemoved, Set<String> edtRemovedFqns,
            IInfobaseConfigurationChange change, IInfobaseUpdateConflictResolver conflictResolver,
            IInfobaseUpdateConflictResolver.IConflictResolveAssist assist, IInfobaseSynchronizationFlow flow,
            IProgressMonitor monitor) throws InfobaseSynchronizationException {
        if (notEmpty(edtChanged) || notEmpty(edtRemoved) || notEmpty(edtRemovedFqns)) {
            observations.edtChanges.set(true);
        }
        if (change == null || change.isEmpty()) {
            // Изменений нет — тот же ответ, что у IDE в этой ветке.
            return new InfobaseConflictResolution(InfobaseConflictResolutionResult.OVERRIDDEN);
        }
        IV8Project v8Project = projects.getProject(project);
        IDtProject dtProject = v8Project == null ? null : v8Project.getDtProject();
        if (dtProject == null) {
            throw new InfobaseSynchronizationException(new Status(IStatus.ERROR, PLUGIN_ID,
                "проект " + project.getName() + " не открыт в 1C:EDT как проект 1С"));
        }
        if (flow != null) {
            flow.cancel();
        }
        IUpdateProjectFlow updateFlow = stateManager.startUpdateProjectFlow(dtProject, infobase);
        // Штатный ассистент ядра — в обёртке: слияние то же, плюс удаление того, что удалено в базе,
        // обновление ресурсов и флаг полной перезагрузки для Retrieval (ReplacingAssist).
        IInfobaseUpdateConflictResolver.IConflictResolveAssist replacing =
            new ReplacingAssist(assist, change, monitor, observations);
        return updateFlow.computeInFlowWithoutFinishing(
            () -> conflictResolver.resolveConflict(project, infobase, edtChanged, edtRemoved, edtRemovedFqns,
                change, replacing, updateFlow, monitor),
            resolution -> resolution.getResolutionResult() != InfobaseConflictResolutionResult.IGNORED);
    }

    private static boolean notEmpty(Set<?> set) {
        return set != null && !set.isEmpty();
    }

    /**
     * Штатный ассистент ядра, который делает слияние настоящей ЗАМЕНОЙ содержимого проекта.
     *
     * <p><b>Зачем.</b> {@code InfobaseConnectionConflictResolveAssist.mergeInfobaseChanges} ядра делает
     * только {@code FileUtil.copyRecursively(temp, project)}: файлы добавляются и перезаписываются, но не
     * удаляются. Живой прогон 1.24.0: после загрузки {@code .dt} без общего модуля модуль пропал из модели,
     * а {@code src/CommonModules/…} остался на диске. К тому же файлы пишутся мимо workspace — обёртка
     * обновляет ресурсы проекта ({@code refreshLocal}) сразу после слияния. «Грязным» после обновления
     * проект делает не это: EDT записывает подтянутые из базы ресурсы с пустой подписью (L8, см.
     * {@code SyncV2.markSynchronized}) — отметку «совпадает с базой» ставит
     * {@code ProjectFromInfobaseUpdater}. Для неё обёртка записывает флаг {@code fullReload} слияния.
     *
     * <p><b>Что делает.</b> До слияния вычисляет устаревшие файлы проекта (вне {@code src} ничего не
     * трогается):
     * <ul>
     * <li>полная перезагрузка — во временном каталоге вся конфигурация базы: устарел любой файл
     *   {@code src}, которого там нет по тому же относительному пути;</li>
     * <li>инкрементальная — во временном каталоге изменённые объекты целиком: объект с внешними
     *   свойствами (модули, справка, команды — проверено выгрузкой {@code -listFile} на 8.5.1) и формы,
     *   макеты, перерасчёты, которые ядро добавляет в выгрузку само ({@code collectChildren}). Внутри
     *   такого объекта устарели его файлы и файлы этих подпапок, которых нет во временном каталоге;
     *   вложенные подсистемы (и таблицы/кубы внешних источников) в выгрузку объекта не входят и не
     *   сверяются. Плюс папки объектов, удалённых в базе ({@code DELETED}): верхнего уровня
     *   ({@code Вид.Имя} — папка {@code src/<Вид>s/<Имя>} с корнем {@code mdclass:<Вид>} в {@code .mdo}) и
     *   вложенных подсистем — по тому же правилу «нет во временном каталоге»: при {@code DELETED} и
     *   {@code NEW} одного {@code Вид.Имя} (объект пересоздан) ядро только что записало новый объект по
     *   тем же путям.</li>
     * </ul>
     * Пути сравниваются без учёта регистра (NTFS; переименование только регистром). Удаляются только
     * обычные файлы внутри настоящего {@code src}: символьные ссылки и точки соединения NTFS не
     * обходятся ({@code Files.walk} спустился бы в junction как в каталог), каждый кандидат проверяется
     * по {@code toRealPath()}, пути из имён платформы — тоже. Файлы поддержки
     * ({@code src/Configuration/ParentConfigurations}, {@code Configuration.distr}) не удаляются
     * никогда. Затем — штатное слияние; если его статус не {@code ERROR}/{@code CANCEL},
     * устаревшие файлы удаляются вместе с опустевшими каталогами (сам {@code src} остаётся), и проект
     * обновляется ({@code refreshLocal}) — до того, как будущее вернётся ядру. Сбои (файл не удалился,
     * обновление ресурсов упало, во временном каталоге нет
     * {@code src}) понижают статус до {@code WARNING} с текстом — {@code ProjectFromInfobaseUpdater}
     * отдаёт его в {@code Outcome.warning}.
     *
     * <p>Класс вложен в {@link HeadlessInfobaseChangesResolver} и, как он, в Guice не биндится: интерфейс
     * ассистента упоминает типы API v2.
     */
    public static final class ReplacingAssist implements IInfobaseUpdateConflictResolver.IConflictResolveAssist {

        private static final String SRC = "src";
        private static final String CONFIGURATION = "Configuration";
        /** Подпапки объекта, которые целиком входят в его выгрузку (внешние свойства и дочерние объекты). */
        private static final Set<String> OBJECT_SUBFOLDERS =
            Set.of("Forms", "Templates", "Recalculations", "Commands", "Help", "Attributes", "Items", "_files");
        private static final String SUBSYSTEM = "Subsystem";
        private static final String SUBSYSTEMS_FOLDER = "Subsystems";
        private static final int MAX_NAMED_FILES = 10;
        private static final Pattern MDO_ROOT = Pattern.compile("<\\s*mdclass:([A-Za-z]+)[\\s>/]");

        private final IInfobaseUpdateConflictResolver.IConflictResolveAssist delegate;
        private final IInfobaseConfigurationChange change;
        private final IProgressMonitor monitor;
        /** Сюда записываются полная перезагрузка и ключи записанного/удалённого слиянием (L8). */
        private final Observations observations;

        public ReplacingAssist(IInfobaseUpdateConflictResolver.IConflictResolveAssist delegate,
                               IInfobaseConfigurationChange change, IProgressMonitor monitor) {
            this(delegate, change, monitor, new Observations());
        }

        public ReplacingAssist(IInfobaseUpdateConflictResolver.IConflictResolveAssist delegate,
                               IInfobaseConfigurationChange change, IProgressMonitor monitor,
                               Observations observations) {
            this.delegate = delegate;
            this.change = change;
            this.monitor = monitor;
            this.observations = observations;
        }

        /**
         * Ключи файлов каталога в формате EDT — ровно как у {@code IResourceStoreManager.getSignaturesForExternalFiles}
         * ({@code ResourceStoreManager$2}, dt.core 27.0.2, по байткоду): {@code Files.walkFileTree} без
         * {@code FOLLOW_LINKS}, ключ каждого файла, пришедшего в {@code visitFile}, —
         * {@code dir.relativize(file).toString().replace("\\", "/")} (фильтра по типу файла у EDT нет — у нас
         * тоже), сбой обхода — исключение. Отличие одно (fix round 5, M4): без SHA-256 содержимого. EDT уже
         * посчитала подписи временного каталога в {@code processIncomingInfobaseResources}, а второй проход по
         * гигабайтам полной перезагрузки шёл бы внутри слияния, под замком базы, — а нам нужны только ключи.
         */
        public static Set<String> fileKeys(Path dir) throws IOException {
            Set<String> keys = new HashSet<>();
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    keys.add(dir.relativize(file).toString().replace("\\", "/"));
                    return FileVisitResult.CONTINUE;
                }
            });
            return keys;
        }

        @Override
        public void exportFullXmlFromInfobase(Path target) throws InfobaseSynchronizationException {
            delegate.exportFullXmlFromInfobase(target);
        }

        @Override
        public void exportIncrementalXmlFromInfobase(Set<String> fqns, Path target)
                throws InfobaseSynchronizationException {
            delegate.exportIncrementalXmlFromInfobase(fqns, target);
        }

        @Override
        public void importIncrementalObjectsToInfobase(Set<EObject> changed, Set<EObject> removed,
                Set<String> removedFqns, IUpdateInfobaseFlow flow)
                throws InfobaseSynchronizationException {
            delegate.importIncrementalObjectsToInfobase(changed, removed, removedFqns, flow);
        }

        @Override
        public void importFullConfigurationToInfobase(Configuration configuration,
                IUpdateInfobaseFlow flow)
                throws InfobaseSynchronizationException {
            delegate.importFullConfigurationToInfobase(configuration, flow);
        }

        @Override
        public CompletableFuture<IStatus> mergeInfobaseChanges(IProject project, Set<String> projectChanges,
                Path tempFolder, Set<String> infobaseChanges, boolean fullReload) {
            // Полная перезагрузка заменяет проект целиком — по ней ProjectFromInfobaseUpdater решает, можно
            // ли отметить проект совпадающим с базой. Слияний несколько — достаточно одной полной.
            if (fullReload) observations.fullReload.set(true);
            Path projectSrc = projectSrc(project);
            Stale stale = projectSrc == null
                ? new Stale(List.of(), null, "не удалось определить каталог проекта " + project.getName()
                    + " — удалённые в базе объекты из проекта не удалены")
                : staleFiles(projectSrc, tempFolder.resolve(SRC), fullReload);
            recordMergedKeys(projectSrc, tempFolder, stale);
            CompletableFuture<IStatus> merged =
                delegate.mergeInfobaseChanges(project, projectChanges, tempFolder, infobaseChanges, fullReload);
            // Будущее ядра уже завершено (copyRecursively синхронный) — thenApply отработает прямо здесь,
            // до возврата: чистка и refreshLocal случатся раньше, чем EDT запишет состояние синхронизации.
            return merged.thenApply(status -> afterMerge(status, project, projectSrc, stale));
        }

        @Override
        public Path createTempDirectory(String prefix) throws InfobaseSynchronizationException {
            return delegate.createTempDirectory(prefix);
        }

        /**
         * Ключи того, что слияние сейчас запишет и удалит (L8, fix round 4) — по ним
         * {@code ProjectFromInfobaseUpdater} отличает изменения самого обновления от чужих. Формат — как у
         * EDT: файлы временного каталога — {@link #fileKeys} (каталог повторяет корень проекта; так же EDT
         * сверяет входящие ресурсы с {@code getEffectiveResourceMetadata}); устаревшие файлы — путь от корня
         * проекта через {@code /}. Не узнать — причина в {@link Observations#mergedKeysFailure()}: отметки не
         * будет. Идёт внутри слияния EDT — ничего не бросает.
         */
        private void recordMergedKeys(Path projectSrc, Path tempFolder, Stale stale) {
            try {
                observations.mergedKeys.addAll(fileKeys(tempFolder));
            } catch (IOException | RuntimeException e) {
                observations.mergedKeysFailure.compareAndSet(null, McpJobs.describe(e));
            }
            if (projectSrc == null) return;
            // Идёт внутри слияния EDT: ничего не бросаем — сбой только лишает отметки.
            try {
                Path root = projectSrc.getParent();
                for (Path file : stale.files()) {
                    observations.mergedKeys.add(root.relativize(file).toString().replace('\\', '/'));
                }
            } catch (RuntimeException e) {
                observations.mergedKeysFailure.compareAndSet(null, McpJobs.describe(e));
            }
        }

        /**
         * Устаревшие файлы проекта, настоящий путь его {@code src} (им проверяется каждый файл перед
         * удалением) и предупреждение, если сверка не состоялась.
         */
        private record Stale(List<Path> files, Path realSrc, String warning) {}

        /**
         * Что есть во временном каталоге: относительные пути от {@code src} без учёта регистра. На NTFS
         * {@code Каталог/Товары} и {@code Каталог/товары} — одно и то же, и переименование только
         * регистром ({@code DELETED Catalog.товары} + {@code NEW Catalog.Товары}) не должно удалить то, что
         * ядро только что записало.
         */
        private record TempFiles(Set<String> keys) {
            boolean contains(Path relativeToSrc) {
                return keys.contains(key(relativeToSrc));
            }
        }

        private static Path projectSrc(IProject project) {
            IPath location = project.getLocation();
            return location == null ? null : location.toFile().toPath().resolve(SRC);
        }

        private Stale staleFiles(Path projectSrc, Path tempSrc, boolean fullReload) {
            if (!Files.isDirectory(tempSrc)) {
                return new Stale(List.of(), null, "во временном каталоге слияния нет src — удалённые в базе объекты "
                    + "из проекта не удалены");
            }
            if (!Files.isDirectory(projectSrc)) return new Stale(List.of(), null, null);
            Set<Path> stale = new LinkedHashSet<>();
            try {
                Path realSrc = projectSrc.toRealPath();
                TempFiles temp = tempFiles(tempSrc);
                if (fullReload) {
                    if (!temp.contains(Path.of(CONFIGURATION, CONFIGURATION + ".mdo"))) {
                        return new Stale(List.of(), realSrc, "полная загрузка без src/Configuration/Configuration.mdo "
                            + "во временном каталоге — удалённые в базе объекты из проекта не удалены");
                    }
                    collectAbsent(projectSrc, realSrc, projectSrc, temp, stale);
                } else {
                    changedObjects(projectSrc, realSrc, tempSrc, temp, stale);
                    deletedObjects(projectSrc, realSrc, temp, stale);
                }
                return new Stale(new ArrayList<>(stale), realSrc, null);
            } catch (IOException | RuntimeException e) {
                return new Stale(List.of(), null, "сверка проекта с версией из базы не удалась ("
                    + McpJobs.describe(e) + ") — удалённые в базе объекты из проекта не удалены");
            }
        }

        private static TempFiles tempFiles(Path tempSrc) throws IOException {
            Set<String> keys = new HashSet<>();
            walkRegularFiles(tempSrc, file -> keys.add(key(tempSrc.relativize(file))));
            return new TempFiles(keys);
        }

        /** Ключ сравнения путей: разделитель {@code /}, без учёта регистра. */
        private static String key(Path relative) {
            return relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        }

        /**
         * Устаревшие файлы под {@code dir}: обычные файлы проекта, которых нет во временном каталоге по тому
         * же пути от {@code src} (без учёта регистра), кроме файлов поддержки — и только внутри настоящего
         * {@code src}. Так же отбираются и файлы папки удалённого объекта: если ядро только что записало по
         * тем же путям новый объект (в изменениях и {@code DELETED}, и {@code NEW} одного {@code Вид.Имя}),
         * его файлы есть во временном каталоге и остаются.
         */
        private static void collectAbsent(Path projectSrc, Path realSrc, Path dir, TempFiles temp,
                                          Set<Path> stale) throws IOException {
            walkRegularFiles(dir, file -> {
                Path relative = projectSrc.relativize(file);
                if (!isSupportFile(relative) && !temp.contains(relative) && insideRealSrc(file, realSrc)) {
                    stale.add(file);
                }
            });
        }

        /**
         * Изменённые объекты — папки во временном {@code src}: {@code Configuration} и
         * {@code <Вид>s/<Имя>} с собственным {@code <Имя>.mdo} (иначе выгрузка объекта неполная и
         * сверять её нельзя).
         */
        private static void changedObjects(Path projectSrc, Path realSrc, Path tempSrc, TempFiles temp,
                                           Set<Path> stale) throws IOException {
            for (Path kind : realDirectories(tempSrc)) {
                String kindName = kind.getFileName().toString();
                if (CONFIGURATION.equals(kindName)) {
                    if (Files.isRegularFile(kind.resolve(CONFIGURATION + ".mdo"), LinkOption.NOFOLLOW_LINKS)) {
                        changedObject(projectSrc, realSrc, projectSrc.resolve(CONFIGURATION), temp, stale);
                    }
                    continue;
                }
                for (Path object : realDirectories(kind)) {
                    String name = object.getFileName().toString();
                    if (Files.isRegularFile(object.resolve(name + ".mdo"), LinkOption.NOFOLLOW_LINKS)) {
                        changedObject(projectSrc, realSrc, projectSrc.resolve(kindName).resolve(name), temp, stale);
                    }
                }
            }
        }

        /** Собственные файлы объекта и файлы подпапок {@link #OBJECT_SUBFOLDERS}, которых нет в его выгрузке. */
        private static void changedObject(Path projectSrc, Path realSrc, Path projectObject, TempFiles temp,
                                          Set<Path> stale) throws IOException {
            if (!isRealDirectoryInside(projectObject, projectSrc, realSrc)) return;
            try (Stream<Path> children = Files.list(projectObject)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    BasicFileAttributes attributes = attributes(child);
                    if (attributes == null || isLinkOrReparsePoint(attributes)) continue;
                    Path relative = projectSrc.relativize(child);
                    if (attributes.isRegularFile()) {
                        if (!isSupportFile(relative) && !temp.contains(relative) && insideRealSrc(child, realSrc)) {
                            stale.add(child);
                        }
                    } else if (attributes.isDirectory()
                            && OBJECT_SUBFOLDERS.contains(child.getFileName().toString())) {
                        collectAbsent(projectSrc, realSrc, child, temp, stale);
                    }
                }
            }
        }

        /** Папки объектов, удалённых в базе: верхнего уровня и вложенных подсистем. */
        private void deletedObjects(Path projectSrc, Path realSrc, TempFiles temp, Set<Path> stale)
                throws IOException {
            if (change == null || change.getObjectChanges() == null) return;
            for (ObjectChange objectChange : change.getObjectChanges()) {
                if (objectChange == null || objectChange.getType() != ObjectChangeType.DELETED
                        || objectChange.getPlatformQualifiedName() == null) {
                    continue;
                }
                String[] parts = objectChange.getPlatformQualifiedName().split("\\.");
                Path folder = null;
                if (parts.length == 2) {
                    folder = topLevelFolder(projectSrc, realSrc, parts[0], parts[1]);
                } else if (isNestedSubsystem(parts)) {
                    folder = nestedSubsystemFolder(projectSrc, realSrc, parts);
                }
                // Не «всё подряд»: то, что ядро только что записало по тем же путям, остаётся.
                if (folder != null) collectAbsent(projectSrc, realSrc, folder, temp, stale);
            }
        }

        /**
         * Папка {@code src/<любой вид>/<Имя>}, чей {@code <Имя>.mdo} начинается с {@code mdclass:<Вид>}, —
         * настоящий каталог внутри {@code src} (путь собран из имени платформы: ни ссылки, ни выхода наружу).
         */
        private static Path topLevelFolder(Path projectSrc, Path realSrc, String kind, String name)
                throws IOException {
            if (CONFIGURATION.equals(kind) || name.isEmpty()) return null;
            for (Path kindFolder : realDirectories(projectSrc)) {
                Path folder = kindFolder.resolve(name);
                if (isRealDirectoryInside(folder, projectSrc, realSrc)
                        && kind.equals(mdoKind(folder.resolve(name + ".mdo")))) {
                    return folder;
                }
            }
            return null;
        }

        /** {@code Subsystem.A.Subsystem.B[.Subsystem.C…]} — от двух уровней вложенности. */
        private static boolean isNestedSubsystem(String[] parts) {
            if (parts.length < 4 || parts.length % 2 != 0) return false;
            for (int i = 0; i < parts.length; i += 2) {
                if (!SUBSYSTEM.equals(parts[i]) || parts[i + 1].isEmpty()) return false;
            }
            return true;
        }

        private static Path nestedSubsystemFolder(Path projectSrc, Path realSrc, String[] parts) {
            Path folder = projectSrc.resolve(SUBSYSTEMS_FOLDER).resolve(parts[1]);
            for (int i = 3; i < parts.length; i += 2) {
                folder = folder.resolve(SUBSYSTEMS_FOLDER).resolve(parts[i]);
            }
            String name = parts[parts.length - 1];
            return isRealDirectoryInside(folder, projectSrc, realSrc)
                && SUBSYSTEM.equals(mdoKind(folder.resolve(name + ".mdo"))) ? folder : null;
        }

        /** Вид объекта по корневому элементу {@code .mdo}; нет файла или не распознан — {@code null}. */
        private static String mdoKind(Path mdo) {
            if (!Files.isRegularFile(mdo, LinkOption.NOFOLLOW_LINKS)) return null;
            try (InputStream in = Files.newInputStream(mdo)) {
                String head = new String(in.readNBytes(2048), StandardCharsets.UTF_8);
                Matcher m = MDO_ROOT.matcher(head);
                return m.find() ? m.group(1) : null;
            } catch (IOException e) {
                return null;
            }
        }

        /**
         * Обходит обычные файлы под {@code dir}, не заходя ни в символьные ссылки, ни в точки повторного
         * анализа NTFS (junction): {@code Files.walk} без {@code FOLLOW_LINKS} считает точку соединения
         * каталогом и спускается в неё — а за ней может быть что угодно вне проекта.
         */
        private static void walkRegularFiles(Path dir, Consumer<Path> action) throws IOException {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return isLinkOrReparsePoint(attributes) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && !isLinkOrReparsePoint(attributes)) action.accept(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // Нечитаемое не удаляется — пропускаем.
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        /** Настоящие подкаталоги (не ссылки и не точки соединения). */
        private static List<Path> realDirectories(Path dir) throws IOException {
            List<Path> out = new ArrayList<>();
            BasicFileAttributes own = attributes(dir);
            if (own == null || !own.isDirectory() || isLinkOrReparsePoint(own)) return out;
            try (Stream<Path> list = Files.list(dir)) {
                list.forEach(child -> {
                    BasicFileAttributes attributes = attributes(child);
                    if (attributes != null && attributes.isDirectory() && !isLinkOrReparsePoint(attributes)) {
                        out.add(child);
                    }
                });
            }
            return out;
        }

        /** Атрибуты самого пути, без перехода по ссылке; нет пути или не читается — {@code null}. */
        private static BasicFileAttributes attributes(Path path) {
            try {
                return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        /**
         * Символьная ссылка или точка повторного анализа: у точки соединения NTFS Java видит
         * {@code isDirectory() = true} и {@code isOther() = true}.
         */
        private static boolean isLinkOrReparsePoint(BasicFileAttributes attributes) {
            return attributes.isSymbolicLink() || attributes.isOther();
        }

        /**
         * Каталог, собранный из имени платформы: лексически внутри {@code src}, сам не ссылка и по настоящему
         * пути тоже внутри настоящего {@code src}.
         */
        private static boolean isRealDirectoryInside(Path folder, Path projectSrc, Path realSrc) {
            if (!folder.normalize().startsWith(projectSrc.normalize())) return false;
            BasicFileAttributes attributes = attributes(folder);
            if (attributes == null || !attributes.isDirectory() || isLinkOrReparsePoint(attributes)) return false;
            return insideRealSrc(folder, realSrc);
        }

        /** Настоящий путь (со всеми ссылками раскрытыми) — внутри настоящего {@code src}. */
        private static boolean insideRealSrc(Path path, Path realSrc) {
            if (realSrc == null) return false;
            try {
                return path.toRealPath().startsWith(realSrc);
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        /** Файлы поддержки: их EDT строит из данных поддержки базы, и удалять их по сверке нельзя. */
        private static boolean isSupportFile(Path relativeToSrc) {
            if (relativeToSrc.getNameCount() < 2 || !CONFIGURATION.equals(relativeToSrc.getName(0).toString())) {
                return false;
            }
            String second = relativeToSrc.getName(1).toString();
            return "ParentConfigurations".equals(second)
                || (relativeToSrc.getNameCount() == 2 && "Configuration.distr".equals(second));
        }

        /**
         * После штатного слияния: при статусе не {@code ERROR}/{@code CANCEL} — удаление устаревших файлов,
         * чистка опустевших каталогов и {@code refreshLocal}; сбои — в статус {@code WARNING}.
         */
        private IStatus afterMerge(IStatus status, IProject project, Path projectSrc, Stale stale) {
            if (status == null || status.matches(IStatus.ERROR | IStatus.CANCEL)) return status;
            List<String> problems = new ArrayList<>();
            if (stale.warning() != null) problems.add(stale.warning());
            List<String> undeleted = delete(stale.files(), projectSrc, stale.realSrc());
            if (!undeleted.isEmpty()) {
                problems.add("не удалось удалить устаревшие файлы проекта (" + undeleted.size() + "): "
                    + names(undeleted));
            }
            try {
                project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            } catch (CoreException | RuntimeException e) {
                problems.add("обновление ресурсов проекта после слияния завершилось ошибкой: " + McpJobs.describe(e));
            }
            if (problems.isEmpty()) return status;
            String own = String.join("; ", problems);
            boolean hasMessage = !status.isOK() && status.getMessage() != null && !status.getMessage().isBlank();
            return new Status(IStatus.WARNING, PLUGIN_ID, hasMessage ? status.getMessage() + "; " + own : own);
        }

        /**
         * Удаляет файлы и опустевшие каталоги над ними до {@code src} (сам {@code src} остаётся). Перед
         * удалением каждый файл проверяется ещё раз: всё ещё обычный файл (не ссылка) внутри настоящего
         * {@code src} — иначе не удаляется и попадает в список неудалённых.
         */
        private static List<String> delete(List<Path> files, Path projectSrc, Path realSrc) {
            List<String> failed = new ArrayList<>();
            Set<Path> parents = new LinkedHashSet<>();
            for (Path file : files) {
                BasicFileAttributes attributes = attributes(file);
                if (attributes == null) continue; // уже нет
                String shown = SRC + "/" + projectSrc.relativize(file).toString().replace('\\', '/');
                if (!attributes.isRegularFile() || isLinkOrReparsePoint(attributes) || !insideRealSrc(file, realSrc)) {
                    failed.add(shown);
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException | RuntimeException e) {
                    failed.add(shown);
                }
                parents.add(file.getParent());
            }
            List<Path> deepestFirst = new ArrayList<>(parents);
            deepestFirst.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path dir : deepestFirst) {
                for (Path d = dir; d != null && d.startsWith(projectSrc) && !d.equals(projectSrc);
                        d = d.getParent()) {
                    if (!isEmptyRealDirectory(d)) break;
                    try {
                        Files.delete(d);
                    } catch (IOException | RuntimeException e) {
                        break;
                    }
                }
            }
            return failed;
        }

        /** Пустой настоящий каталог: точку соединения (даже «пустую») не трогаем. */
        private static boolean isEmptyRealDirectory(Path dir) {
            BasicFileAttributes attributes = attributes(dir);
            if (attributes == null || !attributes.isDirectory() || isLinkOrReparsePoint(attributes)) return false;
            try (Stream<Path> list = Files.list(dir)) {
                return list.findAny().isEmpty();
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        private static String names(List<String> files) {
            List<String> shown = files.size() > MAX_NAMED_FILES ? files.subList(0, MAX_NAMED_FILES) : files;
            String more = files.size() > MAX_NAMED_FILES ? " и ещё " + (files.size() - MAX_NAMED_FILES) : "";
            return String.join(", ", shown) + more;
        }
    }
}
