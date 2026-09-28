package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.resource.IResourceStoreManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.wiring.ServiceAccess;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;

/**
 * «Проект ← база»: конфигурация связанной базы забирается в проект (конфигурации или расширения),
 * содержимое проекта заменяется версией из базы.
 *
 * <p><b>Линковка без API v2 (код-ревью Task 6).</b> Класс биндится в Guice, поэтому обязан
 * загружаться и линковаться и на EDT без API v2. Тип грузится не от любого упоминания в байткоде,
 * а в двух случаях: Guice при создании инжектора читает рефлексией сигнатуры конструкторов,
 * методов и полей; верификатор при линковке ЦЕЛОГО класса (всех методов, в том числе ни разу не
 * вызванных) проверяет присваиваемость — если значение передаётся, возвращается или сохраняется
 * туда, где ожидается тип с ДРУГИМ именем, он загружает этот тип, чтобы узнать, интерфейс ли это
 * и кто чей наследник. {@code checkcast}, вызовы методов ({@code invoke*}) и class-литералы
 * ({@code ldc}) резолвят тип лениво, при первом исполнении.
 *
 * <p>Старая версия этого файла создавала {@code HeadlessInfobaseChangesResolver} прямо в
 * {@code update()} и передавала его в {@code retrieveInfobaseChanges}, чей параметр имеет тип
 * {@code IInfobaseChangesResolver}, — присваиваемость к типу с другим именем. Верификатор загружал
 * {@code IInfobaseChangesResolver}, и на EDT без API v2 класс падал {@code NoClassDefFoundError}
 * ещё при загрузке, то есть валил создание Guice-инжектора целиком, хотя {@code update()} никогда
 * не вызывался. Теперь ни типы v2 (пакет {@code ...sync.v2.*}), ни
 * {@code IInfobaseChangesResolver}/{@code InfobaseSyncResolution}/{@code InfobaseChangesResolutionResult}
 * в этом файле не встречаются вовсе. Единственная связь с синхронизацией — вызов статического
 * {@link HeadlessInfobaseChangesResolver#retrieve} (класс в Guice не биндится): {@code invokestatic}
 * резолвит класс лениво, а аргументы и результат совпадают по имени типа с его сигнатурой.
 * Отметка «проект совпадает с базой» — через {@link SyncV2#markSynchronized} (внутренний API EDT
 * только рефлексией); в {@code @Inject}-конструкторе — только службы, которые есть во всех ветках EDT
 * ({@code IBmModelManager} — тоже). Службу подписей ресурсов ({@code IResourceStoreManager}) класс берёт
 * лениво, в момент вызова, и держит как {@code Object}; значения подписей ({@code EdtResourceMetadata}) в
 * байткоде не упоминаются. Проверка — {@code ProjectFromInfobaseUpdaterLinkageTest} (прячет и весь пакет
 * {@code dt.core.resource}, сверяет параметры {@code @Inject}-конструктора и привязки модуля).
 */
public class ProjectFromInfobaseUpdater {

    /**
     * Итог {@link HeadlessInfobaseChangesResolver#retrieve}: имя результата
     * ({@code InfobaseChangesResolutionResult.name()}, или {@code null}), future отложенного
     * разрешения конфликта (или {@code null}), была ли перезагрузка полной — флаг, с которым EDT
     * вызвала слияние ({@code false}, если слияния не было), передала ли EDT обработчику изменения на
     * своей стороне ({@code edtChanges}), ключи записанного и удалённого слиянием ({@code mergedKeys}, формат
     * EDT) и почему их узнать не удалось ({@code mergedKeysFailure}, иначе {@code null}). Специально без
     * типов v2/sync: с записью работает {@code update()} этого Guice-биндящегося класса, и значение такого
     * типа, переданное или возвращённое там как тип с другим именем, верификатор загрузил бы ещё при
     * линковке класса.
     */
    public record Retrieval(String result, CompletableFuture<IStatus> pending, boolean fullReload,
                            boolean edtChanges, Set<String> mergedKeys, String mergedKeysFailure) {}

    /**
     * Как EDT разрешила изменения базы, сколько это заняло, и предупреждение: слияние завершилось
     * статусом {@code WARNING} (например, устаревший файл проекта не удалился), не удалось обновить
     * ресурсы проекта, или после успешного обновления EDT всё ещё не считает проект совпадающим с
     * базой (база не менялась с последней синхронизации, и EDT не вызвала наш обработчик вовсе, см.
     * {@code NO_CHANGES}; проект не отмечен синхронизированным — см. {@link #update}), или {@code NO_CHANGES} мог
     * прийти без сравнения (шлюз EDT не открыт или быструю проверку не сбросить — F4, fix round 7), или с
     * {@code discardProjectChanges} проект не заменён (аномалия). {@code null} — предупреждений нет. Двухаргументный конструктор оставлен для существующих и будущих
     * вызывающих, которым предупреждение не нужно.
     */
    public record Outcome(String resolution, long durationMs, String warning) {
        public Outcome(String resolution, long durationMs) {
            this(resolution, durationMs, null);
        }
    }

    private static final String RESULT_NO_CHANGES = "NO_CHANGES";
    private static final String RESULT_CHANGES_RESOLVED = "CHANGES_RESOLVED";
    private static final String RESULT_CHANGES_NOT_RESOLVED = "CHANGES_NOT_RESOLVED";
    private static final String MARK_FAILED = "не удалось отметить проект синхронизированным: ";
    private static final int MAX_NAMED_KEYS = 10;

    private final IInfobaseSynchronizationManager sync;
    private final SyncV2 syncV2;
    private final IV8ProjectManager projects;
    private final IBmModelManager models;
    /** Операции конфигуратора: закрытие сеанса агента EDT на базе перед забором (fix round 7, «шлюз» EDT). */
    private final ThickClientOps ops;
    /** Фоновые проверки состояния синхронизации проектов EDT после запуска или открытия (fix round 8, L9). */
    private final EdtSyncStateJobs edtJobs;
    /** Служба подписей ресурсов EDT ({@code IResourceStoreManager}) как {@code Object} — см. {@link #resourceStore()}. */
    private final Supplier<Object> resourcesLookup;

    /**
     * В Guice — только службы, которые есть во всех ветках EDT. {@code IResourceStoreManager} сюда не
     * входит (fix round 4b): на ветке, где его служба не зарегистрирована, создание этого класса упало бы, и
     * четыре инструмента синхронизации молча пропали бы из tools/list вместо ответа «требуется 1C:EDT 2026.1
     * или новее» (спека 6.4). Службу берём лениво, в момент вызова, как {@code SyncV2} — сервис v2.
     * {@link ThickClientOps} и {@link EdtSyncStateJobs} — свои классы бандла, в сигнатурах только публичный API
     * EDT и Eclipse всех веток.
     */
    @Inject
    public ProjectFromInfobaseUpdater(IInfobaseSynchronizationManager sync, SyncV2 syncV2,
                                      IV8ProjectManager projects, IBmModelManager models, ThickClientOps ops,
                                      EdtSyncStateJobs edtJobs) {
        this(sync, syncV2, projects, models, ops, edtJobs, () -> ServiceAccess.get(IResourceStoreManager.class));
    }

    /** @param resourcesLookup откуда брать службу подписей ресурсов EDT (для тестов — подставная) */
    public ProjectFromInfobaseUpdater(IInfobaseSynchronizationManager sync, SyncV2 syncV2,
                                      IV8ProjectManager projects, IBmModelManager models, ThickClientOps ops,
                                      EdtSyncStateJobs edtJobs, Supplier<Object> resourcesLookup) {
        this.sync = sync;
        this.syncV2 = syncV2;
        this.projects = projects;
        this.models = models;
        this.ops = ops;
        this.edtJobs = edtJobs;
        this.resourcesLookup = resourcesLookup;
    }

    /**
     * Пункты «содержимое будет потеряно» ({@link SyncV2#atRiskMessage}) по проектам, которые сейчас будут
     * перезаписаны, — после ожидания модели EDT каждого (fix round 5, O1): синхронная проверка в
     * {@code call()} инструмента свежую правку ещё не видит (см. {@link #update}). Модели не дождались — пункт
     * «не удалось дождаться…» (fail-closed). Для шага {@code check-projects} сценариев 2 и 3.
     */
    public List<String> projectsAtRiskAfterModelSync(Collection<IProject> projects, InfobaseReference infobase) {
        List<String> atRisk = new ArrayList<>();
        for (IProject project : projects) {
            ProjectCheck checked = check(project, infobase, waitForModel(project));
            if (!checked.clean()) atRisk.add(checked.risk());
        }
        return atRisk;
    }

    /**
     * Флаг {@code true} в {@code retrieveInfobaseChanges} — как у {@code ImportConfigurationChangesJob}
     * IDE («импорт изменений конфигурации в существующий проект»). Он означает «забрать изменения,
     * даже если состояние синхронизации неизвестно» (EDT пишет в трассировку «…requested to pull
     * anyway»; с {@code false} при неизвестном состоянии EDT отвечает {@code UNKNOWN}), — то, что
     * нужно новому проекту (сценарий 1): записанного состояния синхронизации с базой у него нет. Загрузка
     * {@code .dt}/{@code .cfe} записанное состояние, наоборот, НЕ сбрасывает (живой прогон, F4) — см. ниже
     * «Быстрая проверка EDT».
     *
     * <p><b>Отметка «проект совпадает с базой» (живой прогон 1.24.0, L8).</b> Забрав изменения базы,
     * EDT записывает подтянутые ресурсы с пустой подписью и до следующей заливки считает проект
     * «грязным» (так и в IDE; подробности — {@link SyncV2#markSynchronized}): следующий вызов без
     * {@code discardProjectChanges} отказал бы, а {@code deploy_project} после полной перезагрузки залил
     * бы всю конфигурацию. Поэтому после успешного {@code CHANGES_RESOLVED} (в том числе отложенного) и
     * {@code refreshLocal} модель EDT дожидается обработки изменённых файлов
     * ({@code IBmModelManager.waitModelSynchronization}), и проект отмечается синхронизированным —
     * но только когда его содержимое действительно совпадает с базой: перезагрузка была полной (проект
     * заменён целиком) или проект совпадал с базой до обновления (инкрементальное обновление привело его
     * к новой версии базы). «Совпадал» — и по {@code isProjectDirty}, спрошенному ПОСЛЕ ожидания модели
     * (иначе свежую, ещё не импортированную правку быстрая проверка не видит), и по самой EDT: изменений на
     * своей стороне она обработчику не передала. Инкрементальное обновление несовпадавшего проекта не
     * отмечается: объекты, которых оно не коснулось, остались как были, и отметка спрятала бы их правки. Не
     * отмечается и обновление, завершившееся предупреждением (устаревший файл не удалился, ресурсы не
     * обновились): содержимое проекта может не совпадать с базой.
     *
     * <p><b>Правки во время обновления (fix round 4).</b> Подписи ресурсов проекта снимаются дважды
     * ({@code IResourceStoreManager.getEffectiveResourceMetadata}): после ожидания модели до обновления и
     * после ожидания модели перед отметкой. Изменилось, появилось или пропало что-то, кроме записанного и
     * удалённого самим слиянием ({@code Retrieval.mergedKeys}), — правка в IDE или другим инструментом
     * попала бы под отметку и никогда не залилась бы: не отмечаем и называем ключи. Снимок или ключи слияния
     * не получены — не отмечаем (fail-closed). Остаётся окно от второго снимка до чтения подписей самой
     * EDT внутри {@code forceEdtSynchronization} и правки файлов, записанных слиянием, уже после слияния:
     * их ключи исключены как свои. {@code NO_CHANGES} не отмечается никогда. Итог решает повторная проверка
     * {@link SyncV2#projectsAtRisk}; тексты — {@link #dirtyAfterUpdateWarning}.
     *
     * <p><b>Шлюз EDT (fix round 7, R7-1).</b> Соединение EDT с базой после каждого забора ставит себе
     * «внешних изменений нет» ({@code DesignerSessionInfobaseConnection.externalChangesCheckRequired = false}) и,
     * пока так, отвечает {@code NO_CHANGES}, вовсе не спрашивая базу — ни идентификатора поколения, ни
     * {@code -getChanges}. Обратно его ставит только закрытие сеанса агента конфигуратора. Поэтому перед каждым
     * забором, после проверки проекта, — {@link ThickClientOps#releaseDesignerSession} (публичный
     * {@code closeDesignerSession} под замком базы; EDT откроет сеанс сама). Цена — повторное открытие сеанса
     * агента на каждый забор: явный «забрать из базы» важнее скорости.
     *
     * <p><b>Фоновые проверки EDT и чужая синхронизация (живой прогон 1.24.0, L9; fix round 8).</b> После запуска EDT
     * или открытия проекта EDT сама проверяет состояние синхронизации проектов с базами (задание «Обновление
     * состояния синхронизации проекта» — открывает агент и забирает изменения своим обработчиком). Столкновение с
     * ним убивало его агент и срывало нашу отметку. Поэтому: в самом начале — {@link EdtSyncStateJobs#await} (не
     * дождались — отказ, ничего не тронуто); перед закрытием сеанса агента, перед сбросом и перед отметкой —
     * {@link SyncV2#awaitNoActiveFlow} (спящий опрос {@code isFlowActive}). Не кончилась за предел — или не удалось
     * даже спросить, идёт ли она (fail-closed, fix round 9, m4), — сеанс агента не закрываем (не рвём его у чужой
     * синхронизации), сброс не делаем, отметку не ставим — пути «не сделано» с причиной.
     *
     * <p><b>Быстрая проверка EDT (живой прогон 1.24.0, F4).</b> Затем — {@link SyncV2#forceRecheck}: EDT отвечает
     * {@code NO_CHANGES}, не сравнивая конфигурацию, если идентификатор поколения данных базы равен записанному, а
     * загрузка {@code .dt}/{@code .cfe} его не меняет. Сброс делает сравнение настоящим (платформенный
     * {@code -getChanges} против записанного состояния) — если шлюз открыт. {@code ConfigDumpInfo} для полной
     * замены откладывается (переносится рядом) только при открытом шлюзе (R7-2): при закрытом забор ответил бы
     * {@code NO_CHANGES}, ничего не выгрузив. После забора отложенное выбрасывается, если EDT действительно
     * выгрузила конфигурацию целиком, иначе возвращается ({@link SyncV2#settleDumpInfo}; fix round 9, рекомендация
     * 1). Не вышло то или другое — забор идёт как раньше, а {@code NO_CHANGES} несёт предупреждение с причинами
     * ({@link #noChangesWarning}).
     *
     * <p><b>Окно перед забором (fix round 9, m1).</b> Без {@code discardProjectChanges} между первой проверкой и
     * забором — ожидания и закрытие сеанса (до минут): проект проверяется ещё раз прямо перед забором (модель EDT,
     * снимок подписей — он и есть «до» для отметки, проверка); не совпадает — тот же отказ O1.
     *
     * @param discardProjectChanges {@code false} — проект, который после ожидания модели EDT не совпадает с
     *     базой (или совпадение не проверить), не перезаписывается: {@link ProjectsAtRiskException} (текст
     *     {@link SyncV2#atRiskMessage}, пункты — отдельно, fix round 6b) ДО {@code retrieveInfobaseChanges} (fix
     *     round 5, O1; проверка — дважды, m1); база сравнивается с записанным состоянием. {@code true} — полная
     *     замена проекта содержимым базы: без проверки; при открытом шлюзе записанный {@code ConfigDumpInfo} пары
     *     откладывается, EDT выгружает конфигурацию целиком и сливает с {@code fullReload = true} (лишние файлы
     *     проекта удаляет {@code ReplacingAssist}). {@code NO_CHANGES} при {@code true} — аномалия: проект не
     *     заменён. Флаг — всегда явно (fix round 6, N3: неявного «true» больше нет).
     */
    public Outcome update(IProject project, InfobaseReference infobase, boolean discardProjectChanges,
            IProgressMonitor monitor) throws ToolException {
        Object stateManager = syncV2.stateManager();
        if (stateManager == null) throw new ToolException(SyncV2.UNAVAILABLE);
        long t0 = System.currentTimeMillis();
        // L9 (fix round 8): фоновые проверки EDT после запуска или открытия проектов — до всего остального; дёшево,
        // когда их нет. Загрузка .dt/.cfe или только что созданный проект могли поставить новую.
        String edtChecks = edtJobs.await(monitor);
        if (edtChecks != null) {
            throw new ToolException(edtChecks + " — проект " + project.getName() + " из базы не обновлён и не "
                + "тронут: повторите update_project_from_infobase позже");
        }
        // Сначала модель EDT (fix round 4): быстрая проверка isProjectDirty сверяет отметки времени, а отметка
        // ресурсов сдвигается, только когда модель импортировала изменение (EDT ждёт ~4 с после последнего).
        // Свежую правку (write_module, сохранение в IDE) без ожидания не увидят ни проверка, ни снимок подписей.
        ResourceStore store = resourceStore();
        String modelBefore = waitForModel(project);
        // Без discardProjectChanges снимок и проверка повторяются прямо перед забором (m1, fix round 9).
        Snapshot before = snapshot(project, store);
        ProjectCheck checked = check(project, infobase, modelBefore);
        if (!discardProjectChanges && !checked.clean()) {
            // O1: синхронная проверка в call() прошла, а после ожидания модели проект не совпадает с базой
            // (правка ещё не была импортирована). Отказ — до первого разрушительного шага. Пункты — в исключении:
            // сценарий, уже загрузивший в базу .dt/.cfe, заменит общий текст своим (fix round 6b; AfterLoad, 9).
            throw new ProjectsAtRiskException(List.of(checked.risk()));
        }
        // L9 (fix round 8): чужая синхронизация этой базы (например, фоновая проверка проекта EDT) — ждём её, а не
        // закрываем агент у неё из-под ног (M4). Не кончилась за предел — сеанс не трогаем, сброс не делаем.
        String busy = syncV2.awaitNoActiveFlow(infobase, monitor, SyncV2.FLOW_WAIT);
        // R7-1: шлюз EDT — без закрытия сеанса агента забор после прошлого забора отвечает NO_CHANGES, не спросив базу.
        String gateFailure = busy != null ? busy : ops.releaseDesignerSession(launcherProject(project), infobase,
            monitor);
        // F4: всегда — базу могли поменять в обход EDT (.dt, .cfe, конфигуратор), а быстрая проверка EDT по
        // идентификатору поколения этого не видит. Полная замена (ConfigDumpInfo откладывается) — только при
        // открытом шлюзе (R7-2): иначе забор мог бы ответить NO_CHANGES, ничего не выгрузив.
        boolean fullReload = discardProjectChanges && gateFailure == null;
        String busyBeforeRecheck = busy != null ? busy : syncV2.awaitNoActiveFlow(infobase, monitor, SyncV2.FLOW_WAIT);
        SyncV2.Recheck recheck = busyBeforeRecheck != null ? new SyncV2.Recheck(false, busyBeforeRecheck)
            : syncV2.forceRecheck(project, infobase, fullReload);
        if (!discardProjectChanges) {
            // m1 (fix round 9): между первой проверкой и забором — ожидания чужой синхронизации и закрытие сеанса
            // агента (до минут). Правку, сделанную за это время в файле, который потом запишет инкрементальное
            // слияние, оно молча перезаписало бы, а отметка сочла бы своей. Поэтому прямо перед забором — снова
            // модель EDT, снимок подписей и проверка; не совпадает — отказ, как у первой проверки (O1); снимок —
            // новый «до». Без переноса ConfigDumpInfo (он только для полной замены) — возвращать нечего.
            String modelAgain = waitForModel(project);
            before = snapshot(project, store);
            checked = check(project, infobase, modelAgain);
            if (!checked.clean()) throw new ProjectsAtRiskException(List.of(checked.risk()));
        }
        Retrieval retrieval;
        try {
            retrieval = HeadlessInfobaseChangesResolver.retrieve(sync, stateManager, projects, project, infobase,
                monitor);
        } catch (InfobaseSynchronizationException e) {
            throw new ToolException(withNote(failurePrefix(project, infobase) + statusMessage(e),
                failedRestoreNote(syncV2.settleDumpInfo(project, infobase, recheck, false))), e);
        } catch (OperationCanceledException e) {
            String note = failedRestoreNote(syncV2.settleDumpInfo(project, infobase, recheck, false));
            if (note.isEmpty()) throw e;
            throw new OperationCanceledException(withNote(failurePrefix(project, infobase) + McpJobs.describe(e),
                note));
        } catch (RuntimeException e) {
            // Неожиданный сбой внутри retrieveInfobaseChanges/потоков синхронизации (например,
            // IInfobaseSynchronizationFlow.cancel() — по контракту API он бросает внутреннее
            // unchecked-исключение) — не должен долетать до вызывающего без русского контекста.
            throw new ToolException(withNote(failurePrefix(project, infobase) + McpJobs.describe(e),
                failedRestoreNote(syncV2.settleDumpInfo(project, infobase, recheck, false))), e);
        } catch (LinkageError e) {
            syncV2.settleDumpInfo(project, infobase, recheck, false);
            throw e;
        }
        // Рекомендация 1 (fix round 9): отложенный для полной замены ConfigDumpInfo — выбросить, если EDT
        // действительно выгрузила конфигурацию целиком, иначе вернуть на место (или оставить записанный EDT заново).
        SyncV2.DumpInfoSettlement settlement = syncV2.settleDumpInfo(project, infobase, recheck,
            retrieval.fullReload());
        PullPreparation pull = new PullPreparation(gateFailure, recheck, settlement);
        Resolution resolution = normalize(retrieval, project, infobase, monitor);
        String refreshWarning = refresh(project, monitor);
        Mark mark = RESULT_CHANGES_RESOLVED.equals(resolution.result())
            ? tryMark(project, infobase, retrieval, checked, store, before,
                resolution.warning() == null && refreshWarning == null, monitor)
            : null;
        boolean trustedNoChanges = !discardProjectChanges && pull.compared();
        String warning = combineWarnings(resolution.warning(), combineWarnings(refreshWarning,
            combineWarnings(noChangesWarning(resolution.result(), discardProjectChanges, pull),
                combineWarnings(partialReplacementWarning(resolution.result(), discardProjectChanges, retrieval, pull),
                    dirtyAfterUpdateWarning(resolution.result(), mark, trustedNoChanges, project, infobase)))));
        return new Outcome(resolution.result(), System.currentTimeMillis() - t0, warning);
    }

    /**
     * M3 (fix round 8): полная замена запрошена, а EDT забрала изменения инкрементально (сеанс агента не закрыт или
     * сброс не удался — {@code ConfigDumpInfo} остался): обновлены только объекты, изменённые в базе, остальное —
     * как было. Причины — как у {@link #noChangesWarning}; совета залить нет.
     */
    private static String partialReplacementWarning(String resolution, boolean discardProjectChanges,
                                                    Retrieval retrieval, PullPreparation pull) {
        if (!discardProjectChanges || !RESULT_CHANGES_RESOLVED.equals(resolution) || retrieval.fullReload()) {
            return null;
        }
        List<String> reasons = pull.reasons();
        return "полная замена не выполнена (discardProjectChanges: true): " + (reasons.isEmpty() ? "причина неизвестна"
            : String.join("; ", reasons)) + " — EDT обновила только объекты, изменённые в базе; объекты, которых эти "
            + "изменения не коснулись, остались в проекте как были";
    }

    /**
     * Проект, по которому выбирается исполнитель конфигуратора для закрытия сеанса агента: у проекта расширения —
     * его родительская конфигурация, как у операций сценариев 2 и 3 (загрузка и применение {@code .cfe}): база одна,
     * установку EDT выбирает для пары «конфигурация — база». Модель недоступна или родителя нет — сам проект.
     */
    private IProject launcherProject(IProject project) {
        try {
            if (projects.getProject(project) instanceof IExtensionProject extension
                    && extension.getParentProject() != null) {
                return extension.getParentProject();
            }
        } catch (RuntimeException | LinkageError e) {
            // Модель проекта не отдалась — исполнитель выберется по самому проекту.
        }
        return project;
    }

    /**
     * Подготовка забора (fix round 7, 8, 9): закрыт ли сеанс агента конфигуратора (шлюз EDT) — {@code gateFailure ==
     * null} (закрытие прошло без ошибки; открылся ли шлюз на самом деле, это не доказывает — M1), сброшена ли
     * быстрая проверка по идентификатору поколения — {@code recheck}; что стало с записанным {@code ConfigDumpInfo}
     * пары, отложенным для полной замены, после забора — {@code settlement} (рекомендация 1: выброшен, возвращён,
     * оставлен записанный EDT заново или вернуть не удалось; ничего не откладывалось — {@code NONE}, в том числе
     * когда файла и не было, m3).
     */
    private record PullPreparation(String gateFailure, SyncV2.Recheck recheck, SyncV2.DumpInfoSettlement settlement) {

        /** {@code NO_CHANGES} — итог настоящего сравнения базы с записанным состоянием. */
        boolean compared() {
            return gateFailure == null && recheck.done();
        }

        /**
         * Почему {@code NO_CHANGES} мог прийти без сравнения; пусто — таких причин не знаем. Формулировки не
         * утверждают больше, чем известно (M2): причина сброса сама говорит, что успело сделаться. Одна и та же
         * причина у сеанса и у сброса (чужая синхронизация базы дольше предела, сбой проверки {@code isFlowActive})
         * называется один раз.
         */
        List<String> reasons() {
            List<String> reasons = new ArrayList<>();
            if (gateFailure != null && !recheck.done() && gateFailure.equals(recheck.reason())) {
                reasons.add(gateFailure + " — сеанс агента конфигуратора EDT на базе не закрыт и быстрая проверка по "
                    + "идентификатору поколения данных базы не сброшена, и EDT могла ответить, вовсе не спрашивая базу");
                return reasons;
            }
            if (gateFailure != null) {
                reasons.add("сеанс агента конфигуратора EDT на базе не закрыт (" + gateFailure + "), и EDT могла "
                    + "ответить, вовсе не спрашивая базу");
            }
            if (!recheck.done()) {
                reasons.add("сброс быстрой проверки по идентификатору поколения данных базы не выполнен или выполнен "
                    + "не полностью (" + recheck.reason() + ")");
            }
            return reasons;
        }
    }

    /**
     * Предупреждение при {@code NO_CHANGES} (fix round 7, C). С {@code discardProjectChanges} это всегда аномалия —
     * ждали полную замену: проект не заменён, причины (или «неизвестна») и что стало с отложенным
     * {@code ConfigDumpInfo} (fix round 9, рекомендация 1): возвращён — записанное состояние прежнее, перезапуск
     * не нужен; вернуть не удалось — EDT теперь считает пару ни разу не синхронизированной, выход — перезапуск EDT
     * и повтор с флагом. Ни «база не менялась», ни совета залить (заливка отправила бы в базу ту самую правку,
     * которую просили отбросить). Без флага — только если шлюз не открыт или быструю проверку не сбросить (F4, R7-1):
     * ответ мог прийти без сравнения. Иначе {@code NO_CHANGES} правдив — {@code null}.
     */
    private static String noChangesWarning(String resolution, boolean discardProjectChanges, PullPreparation pull) {
        if (!RESULT_NO_CHANGES.equals(resolution)) return null;
        List<String> reasons = pull.reasons();
        if (discardProjectChanges) {
            // M1 (fix round 8): «закрытие без ошибки» — не доказательство, что шлюз EDT открылся.
            String why = reasons.isEmpty()
                ? "причина неизвестна: закрытие сеанса агента конфигуратора прошло без ошибки, быстрая проверка "
                    + "сброшена"
                : String.join("; ", reasons);
            return "проект не заменён содержимым базы, хотя запрошена полная замена (discardProjectChanges: true): "
                + "EDT ответила «изменений нет» — " + why + ". Содержимое проекта не тронуто"
                + settledStateNote(pull.settlement());
        }
        if (reasons.isEmpty()) return null;
        return "EDT ответила «изменений нет», но могла ответить так по быстрой проверке, не сравнивая конфигурацию базы с "
            + "проектом: " + String.join("; ", reasons) + ". Если базу меняли в обход EDT (загрузка .dt или .cfe, "
            + "конфигуратор), проект может с ней не совпадать — проверьте проект";
    }

    /**
     * Хвост аномалии {@code NO_CHANGES} при полной замене: что стало с отложенным {@code ConfigDumpInfo} (fix round
     * 9, рекомендация 1). Ничего не откладывалось — пусто.
     */
    private static String settledStateNote(SyncV2.DumpInfoSettlement settlement) {
        if (settlement == null) return "";
        return switch (settlement.fate()) {
            case RESTORED -> ", записанное состояние синхронизации проекта с базой прежнее — обновление можно "
                + "повторить" + lockNote(settlement.reason());
            case KEPT_EDT_COPY -> ", записанное состояние синхронизации проекта с базой EDT за это время записала "
                + "заново" + lockNote(settlement.reason());
            case RESTORE_FAILED -> ". " + unsyncedAfterFailedRestore(settlement.reason());
            default -> "";
        };
    }

    /**
     * Хвост текста сбоя забора: отложенный {@code ConfigDumpInfo} вернуть не удалось — пара осталась «ни разу не
     * синхронизированной», и это надо сказать; иначе (ничего не откладывалось, возвращён, оставлен записанный EDT)
     * — пусто: состояние синхронизации прежнее.
     */
    private static String failedRestoreNote(SyncV2.DumpInfoSettlement settlement) {
        return settlement != null && settlement.fate() == SyncV2.DumpInfoFate.RESTORE_FAILED
            ? ". " + unsyncedAfterFailedRestore(settlement.reason()) : "";
    }

    /** Отложенный для полной замены {@code ConfigDumpInfo} вернуть не удалось: что это значит и что делать. */
    private static String unsyncedAfterFailedRestore(String reason) {
        return "Записанное состояние синхронизации проекта с базой перед обновлением отложено, а вернуть его не "
            + "удалось (" + reason + "), и EDT теперь считает проект ни разу не синхронизированным с этой базой: "
            + "перезапустите EDT, затем повторите update_project_from_infobase с discardProjectChanges: true (повтор "
            + "без перезапуска может попасть в то же состояние EDT)";
    }

    /** Замок EDT после возврата не снялся ({@code DumpInfoSettlement.reason} у возвращённого) — замечание. */
    private static String lockNote(String reason) {
        return reason == null ? "" : " (" + reason + "; если EDT откажет в синхронизации этой базы, перезапустите EDT)";
    }

    /** Текст сбоя и хвост к нему: точка в конце текста не удваивается. */
    private static String withNote(String text, String note) {
        if (note.isEmpty()) return text;
        String t = text.strip();
        return (t.endsWith(".") ? t.substring(0, t.length() - 1) : t) + note;
    }

    /** Итог по белому списку и текст статуса {@code WARNING} разрешения (или {@code null}). */
    private record Resolution(String result, String warning) {}

    /**
     * Отметка после {@code CHANGES_RESOLVED}: {@code allowed} — содержимое проекта должно совпадать с
     * базой, отмечать можно; {@code unverified} — отмечать нельзя, потому что совпадение до обновления
     * проверить не удалось (причина; M1, fix round 5), а не потому, что EDT считала проект
     * несинхронизированным; {@code failure} — почему проект всё же не отмечен (текст целиком), или
     * {@code null} — отмечен.
     */
    private record Mark(boolean allowed, String unverified, String failure) {}

    /**
     * Проект глазами EDT: {@code risk == null} — совпадает с базой; иначе пункт для {@link SyncV2#atRiskMessage}.
     * {@code unverified} — не {@code null}, если совпадение проверить не удалось (не дождались модели или упала
     * сама проверка EDT), с причиной: это «неизвестно», а не «EDT считает проект несинхронизированным».
     */
    private record ProjectCheck(String risk, String unverified) {
        boolean clean() {
            return risk == null;
        }
    }

    /** @param waitFailure причина, по которой не дождались модели EDT перед проверкой, или {@code null} */
    private ProjectCheck check(IProject project, InfobaseReference infobase, String waitFailure) {
        if (waitFailure != null) {
            String reason = "не удалось дождаться, пока EDT обработает изменения файлов проекта: " + waitFailure;
            return new ProjectCheck(project.getName() + " (" + reason + ")", reason);
        }
        try {
            SyncV2.Risk risk = syncV2.riskOf(project, infobase);
            return risk == null ? new ProjectCheck(null, null) : new ProjectCheck(risk.item(), risk.unverifiedReason());
        } catch (ToolException e) {
            return new ProjectCheck(project.getName() + " (не удалось проверить: " + e.getMessage() + ")",
                e.getMessage());
        }
    }

    /**
     * Модель EDT — после каждого {@code CHANGES_RESOLVED}: и отметка, и повторная проверка, и следующие
     * инструменты должны видеть обработанные файлы. Затем отметка — если она разрешена, обновление прошло
     * без предупреждений и за время обновления в проекте не изменилось ничего, кроме записанного и
     * удалённого самим слиянием (второй снимок подписей — как можно ближе к отметке).
     *
     * <p>Разрешена: полная перезагрузка (проект заменён целиком) или проект совпадал с базой до обновления
     * — и по проверке {@code isProjectDirty} после ожидания модели, и по самой EDT: изменений на своей
     * стороне она обработчику не передала ({@code Retrieval.edtChanges}).
     *
     * @param pullClean ни разрешение изменений, ни обновление ресурсов не дали предупреждения
     * @param monitor для ожидания чужой синхронизации базы перед самой отметкой (fix round 8, L9)
     */
    private Mark tryMark(IProject project, InfobaseReference infobase, Retrieval retrieval, ProjectCheck checked,
            ResourceStore store, Snapshot before, boolean pullClean, IProgressMonitor monitor) {
        String modelAfter = waitForModel(project);
        if (!retrieval.fullReload() && (!checked.clean() || retrieval.edtChanges())) {
            return new Mark(false, checked.unverified(), null);
        }
        if (!pullClean) {
            return new Mark(true, null, "проект не отмечен синхронизированным: обновление завершилось с "
                + "предупреждением, и содержимое проекта может не совпадать с базой — проверьте проект");
        }
        if (modelAfter != null) {
            return notMarked("не удалось дождаться, пока EDT обработает изменённые файлы проекта (" + modelAfter + ")");
        }
        if (before.failure() != null) {
            return notMarked("не удалось снять подписи ресурсов проекта до обновления (" + before.failure() + ")");
        }
        if (retrieval.mergedKeysFailure() != null) {
            return notMarked("не удалось узнать, какие файлы записало обновление (" + retrieval.mergedKeysFailure()
                + ")");
        }
        Snapshot after = snapshot(project, store);
        if (after.failure() != null) {
            return notMarked("не удалось снять подписи ресурсов проекта после обновления (" + after.failure() + ")");
        }
        List<String> foreign = foreignChanges(before.signatures(), after.signatures(),
            retrieval.mergedKeys() == null ? Set.of() : retrieval.mergedKeys());
        if (!foreign.isEmpty()) {
            return new Mark(true, null, "во время обновления в проекте изменились объекты, которых обновление не "
                + "касалось: " + names(foreign) + " — проект не отмечен синхронизированным");
        }
        // L9 (fix round 8): синхронизация базы, начатая, пока шёл наш забор (фоновая проверка проекта EDT), — ждём её
        // конца, а не отказываемся от отметки сразу («другая синхронизация… прямо сейчас»).
        String busy = syncV2.awaitNoActiveFlow(infobase, monitor, SyncV2.FLOW_WAIT);
        if (busy != null) return notMarked(busy);
        SyncV2.Marking marking = syncV2.markSynchronized(project, infobase);
        return marking.marked() ? new Mark(true, null, null) : notMarked(marking.reason());
    }

    private static Mark notMarked(String reason) {
        return new Mark(true, null, MARK_FAILED + reason);
    }

    /**
     * Подписи ресурсов проекта глазами EDT ({@code IResourceStoreManager.getEffectiveResourceMetadata}: ключ —
     * путь от корня проекта через {@code /}, значение — {@code EdtResourceMetadata}); {@code failure} — почему
     * снимок снять не удалось. Значения типизированы как {@code ?}: класс {@code EdtResourceMetadata} в
     * байткоде этого Guice-биндящегося класса не упоминается (его может не быть на старой ветке EDT), а
     * сравниваются они его же {@code equals} (подпись и UUID) через {@code Objects.equals}.
     */
    private record Snapshot(Map<String, ?> signatures, String failure) {}

    /**
     * Служба подписей ресурсов EDT, найденная в момент вызова: {@code service} — {@code IResourceStoreManager}
     * как {@code Object} (тип не звучит ни в сигнатурах, ни в полях этого Guice-биндящегося класса), или
     * {@code null} и {@code failure} — почему её нет.
     */
    private record ResourceStore(Object service, String failure) {}

    /**
     * Как {@code SyncV2} берёт сервис v2: {@code ServiceAccess} в момент вызова, отсутствие класса —
     * {@link LinkageError}, незарегистрированная служба — {@link RuntimeException}. Нет службы — нет снимков
     * подписей, а значит, и отметки (fail-closed); обновление проекта от этого не страдает.
     */
    private ResourceStore resourceStore() {
        try {
            Object service = resourcesLookup.get();
            return service instanceof IResourceStoreManager ? new ResourceStore(service, null)
                : new ResourceStore(null, "сервис ресурсов EDT недоступен");
        } catch (RuntimeException | LinkageError e) {
            return new ResourceStore(null, "сервис ресурсов EDT недоступен: " + McpJobs.describe(e));
        }
    }

    private Snapshot snapshot(IProject project, ResourceStore store) {
        if (store.service() == null) return new Snapshot(null, store.failure());
        try {
            IV8Project v8Project = projects.getProject(project);
            IDtProject dtProject = v8Project == null ? null : v8Project.getDtProject();
            if (dtProject == null) {
                return new Snapshot(null, "проект " + project.getName() + " не открыт в 1C:EDT как проект 1С");
            }
            Map<String, ?> signatures =
                ((IResourceStoreManager) store.service()).getEffectiveResourceMetadata(dtProject);
            return signatures == null ? new Snapshot(null, "EDT не отдала подписи ресурсов проекта")
                : new Snapshot(new HashMap<String, Object>(signatures), null);
        } catch (RuntimeException | LinkageError e) {
            return new Snapshot(null, McpJobs.describe(e));
        }
    }

    /**
     * Ключи ресурсов, которые изменились, появились или пропали между снимками, — кроме записанного и
     * удалённого самим слиянием и их каталогов-предков (на случай, если EDT хранит и записи каталогов).
     * Ключи сравниваются без учёта регистра (NTFS; переименование только регистром), по алфавиту.
     */
    static List<String> foreignChanges(Map<String, ?> before, Map<String, ?> after, Set<String> merged) {
        Set<String> own = new HashSet<>();
        for (String key : merged) {
            String lower = key.toLowerCase(Locale.ROOT);
            own.add(lower);
            for (int slash = lower.lastIndexOf('/'); slash > 0; slash = lower.lastIndexOf('/', slash - 1)) {
                own.add(lower.substring(0, slash));
            }
        }
        Set<String> changed = new TreeSet<>();
        for (Map.Entry<String, ?> entry : before.entrySet()) {
            String key = entry.getKey();
            if (key != null && (!after.containsKey(key) || !Objects.equals(entry.getValue(), after.get(key)))) {
                changed.add(key);
            }
        }
        for (String key : after.keySet()) {
            if (key != null && !before.containsKey(key)) changed.add(key);
        }
        changed.removeIf(key -> own.contains(key.toLowerCase(Locale.ROOT)));
        return new ArrayList<>(changed);
    }

    private static String names(List<String> keys) {
        List<String> shown = keys.size() > MAX_NAMED_KEYS ? keys.subList(0, MAX_NAMED_KEYS) : keys;
        String more = keys.size() > MAX_NAMED_KEYS ? " и ещё " + (keys.size() - MAX_NAMED_KEYS) : "";
        return String.join(", ", shown) + more;
    }

    /**
     * {@code IBmModelManager.waitModelSynchronization} — штатное блокирующее ожидание, пока BM обработает
     * изменения ресурсов проекта (у EDT — не дольше 15 минут); отмена задания его не прерывает.
     *
     * @return {@code null} — дождались, иначе причина
     */
    private String waitForModel(IProject project) {
        try {
            models.waitModelSynchronization(project);
            return null;
        } catch (OperationCanceledException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            return McpJobs.describe(e);
        }
    }

    /**
     * Whitelist допустимых итогов: {@code NO_CHANGES}/{@code CHANGES_RESOLVED} — успех как есть;
     * {@code CHANGES_NOT_RESOLVED} с future, переданным на ожидание — если future разрешился без
     * ошибки, это тоже успех, но под именем {@code CHANGES_RESOLVED} (на момент возврата из
     * {@code retrieveInfobaseChanges} конфликт ещё «не решён», а вот когда future этого дождался —
     * решён). {@code CHANGES_NOT_RESOLVED} без future, {@code CHANGES_IGNORE}, {@code UNKNOWN},
     * любое нераспознанное имя или {@code null} — отказ.
     */
    private Resolution normalize(Retrieval retrieval, IProject project, InfobaseReference infobase,
            IProgressMonitor monitor) throws ToolException {
        String result = retrieval.result();
        if (RESULT_NO_CHANGES.equals(result) || RESULT_CHANGES_RESOLVED.equals(result)) {
            return new Resolution(result, awaitResolution(retrieval.pending(), project, infobase, monitor));
        }
        if (RESULT_CHANGES_NOT_RESOLVED.equals(result) && retrieval.pending() != null) {
            return new Resolution(RESULT_CHANGES_RESOLVED,
                awaitResolution(retrieval.pending(), project, infobase, monitor));
        }
        throw new ToolException("EDT не приняла изменения базы " + infobase.getName() + " в проект "
            + project.getName() + " (" + result + ")");
    }

    /**
     * Разрешение изменений может завершаться асинхронно. Ждём порциями по 5 с, чтобы отмена
     * задания (Progress view, лимит времени) не ждала бесконечно. {@code pending.get(5, SECONDS)} —
     * это блокирующее ожидание с таймаутом (поток спит), а не холостой цикл.
     *
     * <p>Успех — только статус без {@code ERROR}/{@code CANCEL}: так же итог слияния проверяет сама
     * EDT ({@code MergingConflictResolver.postProcessMerge}: {@code status.matches(ERROR | CANCEL)} —
     * неуспех). Статус {@code null} — тоже неуспех: EDT не подтвердила, что проект обновлён. Отмена
     * самого future ({@code CancellationException}, unchecked) превращается в {@link ToolException}.
     *
     * @return текст статуса {@code WARNING} (например, от
     *     {@code HeadlessInfobaseChangesResolver.ReplacingAssist}: устаревший файл проекта не удалился) —
     *     он уходит в {@code Outcome.warning}; иначе {@code null}
     */
    private static String awaitResolution(CompletableFuture<IStatus> pending, IProject project,
            InfobaseReference infobase, IProgressMonitor monitor) throws ToolException {
        if (pending == null) return null;
        IStatus status;
        while (true) {
            try {
                status = pending.get(5, TimeUnit.SECONDS);
                break;
            } catch (TimeoutException e) {
                if (monitor != null && monitor.isCanceled()) throw new OperationCanceledException();
            } catch (CancellationException e) {
                throw new ToolException("EDT отменила обновление проекта " + project.getName() + " из базы "
                    + infobase.getName() + ": ожидание разрешения изменений базы прекращено", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolException("ожидание разрешения изменений базы прервано");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new ToolException("разрешение изменений базы завершилось ошибкой: " + McpJobs.describe(cause), cause);
            }
        }
        if (status == null) {
            throw new ToolException("разрешение изменений базы завершилось без статуса (null): EDT не "
                + "подтвердила, что проект " + project.getName() + " обновлён из базы " + infobase.getName());
        }
        if (status.matches(IStatus.CANCEL)) {
            throw new ToolException("разрешение изменений базы отменено (статус CANCEL): " + status.getMessage());
        }
        if (status.matches(IStatus.ERROR)) {
            throw new ToolException("разрешение изменений базы завершилось ошибкой (статус ERROR): "
                + status.getMessage());
        }
        return status.getSeverity() == IStatus.WARNING && status.getMessage() != null
            && !status.getMessage().isBlank() ? status.getMessage() : null;
    }

    /**
     * @return текст предупреждения при сбое обновления ресурсов, иначе {@code null}. Отмена
     *     пробрасывается как есть — вызывающий (задание/monitor) сам решает, что делать с отменой.
     */
    private static String refresh(IProject project, IProgressMonitor monitor) {
        try {
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            return null;
        } catch (OperationCanceledException e) {
            throw e;
        } catch (CoreException | RuntimeException e) {
            // Ассистент ядра пишет файлы проекта мимо workspace API; обновление ресурсов — страховка.
            // Раньше сбой здесь молча проглатывался — теперь он виден в Outcome.warning().
            return "обновление ресурсов проекта после синхронизации завершилось ошибкой: " + McpJobs.describe(e);
        }
    }

    /**
     * После успешного обновления заново проверяет, считает ли EDT проект совпадающим с базой.
     *
     * <p>{@code NO_CHANGES}: EDT возвращает его, даже не вызывая наш обработчик, если не изменилась
     * САМА база, — содержимое проекта не трогается, и расхождения остаются в проекте, хотя
     * вызывающий ожидал, что они будут отброшены. «База не менялась» говорим, только если {@code NO_CHANGES}
     * правдив: без {@code discardProjectChanges}, шлюз EDT открыт и быстрая проверка сброшена. Иначе (fix round
     * 6b, 7) ответ мог прийти без сравнения, а базу — загрузить из {@code .dt}, или (с флагом) это аномалия: текст
     * нейтральный и без совета залить (про саму базу — {@link #noChangesWarning} рядом). Иначе (содержимое
     * заменено) — по отметке
     * ({@link #update}): отмечать было нельзя (инкрементальное обновление проекта, который EDT считала
     * несинхронизированным ещё до обновления: незалитые правки, сбой проверки, пустые подписи после
     * обновления в IDE) — объекты, которых обновление не коснулось, остались как были; не отмечен —
     * EDT считает подтянутое несинхронизированным, с причиной (для предупреждения обновления — с советом
     * проверить проект); отмечен, но EDT
     * всё равно не считает проект совпадающим с базой — нейтральный текст: EDT может записать
     * состояние синхронизации позже, и «база не менялась… откатите» толкнул бы выбросить только что
     * импортированное. Во всех случаях в тексте — детали {@link SyncV2#projectsAtRisk}. Ни в одном тексте нет ни
     * совета, ни упоминания {@code deploy_project} (fix round 8): после загрузки {@code .dt}/{@code .cfe} и при
     * полной замене заливка перезаписала бы базу проектом, а обновление не знает, по какому пути его позвали.
     *
     * @param mark итог отметки; {@code null} — не {@code CHANGES_RESOLVED}
     * @param trustedNoChanges {@code NO_CHANGES} — итог настоящего сравнения и не аномалия полной замены
     */
    private String dirtyAfterUpdateWarning(String resolution, Mark mark, boolean trustedNoChanges, IProject project,
            InfobaseReference infobase) {
        List<String> atRisk;
        try {
            atRisk = syncV2.projectsAtRisk(List.of(project), infobase);
        } catch (ToolException e) {
            // SyncV2 только что успешно отработал в этом же вызове update() — практически
            // недостижимо; отсутствие warning безопаснее ложной тревоги.
            return null;
        }
        if (atRisk.isEmpty()) return null;
        String details = String.join("; ", atRisk);
        if (RESULT_NO_CHANGES.equals(resolution) && !trustedNoChanges) {
            return "EDT не заменила содержимое проекта: расхождения с базой остались в проекте (" + details + ")";
        }
        if (RESULT_NO_CHANGES.equals(resolution)) {
            return "база не менялась с последней синхронизации, и EDT не заменила содержимое проекта: "
                + "расхождения с базой остались в проекте (" + details + "). Посмотрите их в git; если они "
                + "не нужны — откатите в git.";
        }
        if (mark != null && !mark.allowed()) {
            String why = mark.unverified() != null
                ? "не удалось проверить, совпадал ли проект с базой до обновления (" + mark.unverified() + ")"
                : "EDT считала проект несинхронизированным ещё до обновления";
            return "проект обновлён из базы, но " + why + ": объекты, которых обновление не коснулось, остались как "
                + "были — с незалитыми правками, если они в них были (" + details + ")";
        }
        if (mark != null && mark.failure() != null) {
            return "после обновления EDT всё ещё не считает проект совпадающим с базой (" + details + "): "
                + "EDT считает подтянутые объекты несинхронизированными — так EDT ведёт себя и в IDE; "
                + mark.failure();
        }
        return "после обновления EDT всё ещё не считает проект совпадающим с базой (" + details + "). "
            + "Возможно, запись состояния синхронизации ещё не завершилась — проверьте проект.";
    }

    private static String combineWarnings(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + "; " + b;
    }

    private static String failurePrefix(IProject project, InfobaseReference infobase) {
        return "обновление проекта " + project.getName() + " из базы " + infobase.getName() + " не выполнено: ";
    }

    private static String statusMessage(InfobaseSynchronizationException e) {
        IStatus status = e.getStatus();
        return status != null && status.getMessage() != null ? status.getMessage() : McpJobs.describe(e);
    }
}
