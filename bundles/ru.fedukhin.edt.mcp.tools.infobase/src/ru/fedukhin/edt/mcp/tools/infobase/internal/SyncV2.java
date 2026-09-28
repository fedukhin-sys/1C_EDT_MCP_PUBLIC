package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseChangesResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.wiring.ServiceAccess;
import jakarta.inject.Inject;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;

/**
 * Доступ к API синхронизации v2 ({@code IInfobaseSynchronizationStateManager}), которого нет в
 * старых ветках 1C:EDT.
 *
 * <p><b>Почему не Guice.</b> {@code bind(...).toService()} в {@code ToolsInfobaseModule} на EDT без
 * этого класса уронил бы создание инжектора, а он общий для всех инструментов бандла — перестали
 * бы работать и {@code deploy_project}, и {@code create_infobase}. Поэтому сервис берётся лениво,
 * в момент вызова, а отсутствие класса ловится как {@link LinkageError}.
 *
 * <p><b>Где v2-типам можно появляться.</b> Сам класс биндится в Guice, поэтому его надо загрузить
 * и слинковать и на EDT без API v2. Тип грузится не от любого упоминания в байткоде, а в двух
 * случаях: (1) Guice при создании инжектора читает рефлексией сигнатуры конструкторов, методов и
 * полей — поэтому v2-типов нет ни в сигнатурах, ни в полях этого класса; (2) верификатор при
 * линковке класса проверяет присваиваемость — когда значение передаётся, возвращается или
 * сохраняется туда, где ожидается тип с ДРУГИМ именем, он загружает этот тип. {@code checkcast},
 * вызовы методов ({@code invoke*}) и class-литералы ({@code ldc}) резолвят тип лениво, при первом
 * исполнении. Поэтому в телах методов ниже v2-типы встречаются только так: приведение
 * {@code (IInfobaseSynchronizationStateManager) sm}, вызовы его методов на значении того же типа,
 * class-литералы, — и исполняются они только после того, как {@link #stateManager()} убедился,
 * что API есть. Утверждение проверяет {@code ProjectFromInfobaseUpdaterLinkageTest}: он прячет от
 * загрузчика {@code sync.v2.*}/{@code IInfobaseChangesResolver} и линкует каждый класс модуля.
 */
public class SyncV2 {

    static final String UNAVAILABLE = "инструмент требует 1C:EDT 2026.1 или новее: в этой версии нет API "
        + "синхронизации v2 (IInfobaseSynchronizationStateManager)";

    private final Supplier<Object> lookup;
    private volatile Object cached;
    private volatile RuntimeException transientFailure;

    @Inject
    public SyncV2() {
        this(() -> ServiceAccess.get(IInfobaseSynchronizationStateManager.class));
    }

    public SyncV2(Supplier<Object> lookup) {
        this.lookup = lookup;
    }

    public boolean isAvailable() {
        return stateManager() != null;
    }

    /**
     * Отличает две причины отказа: сервис/классы API v2 отсутствуют совсем (старый EDT, форма
     * callback'а не совпадает) — {@link #UNAVAILABLE}; сервис ЕСТЬ, но {@link #lookup} только что
     * бросил {@link RuntimeException} (например, {@code ServiceUnavailableException} — EDT ещё
     * поднимает реестр сервисов при старте) — отдельное сообщение с подсказкой «повторите позже».
     * Ничего не кэшируется на пути отказа в обоих случаях — следующий вызов пробует заново.
     */
    public void requireAvailable() throws ToolException {
        if (isAvailable()) return;
        RuntimeException cause = transientFailure;
        if (cause != null) {
            throw new ToolException("сервис синхронизации EDT пока недоступен (EDT ещё загружается?): "
                + McpJobs.describe(cause) + "; повторите позже", cause);
        }
        throw new ToolException(UNAVAILABLE);
    }

    /**
     * Сервис как {@code Object}: приводить к типу v2 — дело вызывающего кода. {@code null} — API
     * нет (класс отсутствует, сервис не того типа, форма {@code IInfobaseChangesResolver} не
     * совпадает с ожидаемой) либо сервис временно недоступен ({@link RuntimeException} из
     * {@link #lookup} — деталь смотри в {@link #requireAvailable()}).
     */
    public Object stateManager() {
        Object sm = cached;
        if (sm != null) return sm;
        transientFailure = null;
        Object looked;
        try {
            looked = lookup.get();
        } catch (RuntimeException e) {
            transientFailure = e;
            return null;
        } catch (LinkageError e) {
            return null;
        }
        if (looked == null || !isStateManager(looked) || !matchesExpectedResolverShape()) return null;
        cached = looked;
        return looked;
    }

    /**
     * Проекты, чьё содержимое будет потеряно при перезаписи из базы: есть правки, не залитые в
     * базу, или EDT не знает, совпадает ли проект с базой.
     *
     * <p>{@code hasSynchronizationInfo = false} означает «у EDT нет ConfigDumpInfo для пары проект —
     * база», то есть проект с этой базой ещё ни разу не синхронизировался. Это ровно главный случай
     * инструментов: новый проект и существующая база (или пустой проект расширения, созданный по
     * подсказке). Текст пункта говорит именно это — чтобы вызывающий не принял его за «незалитые
     * правки» и не пошёл заливать пустой проект в базу.
     */
    public List<String> projectsAtRisk(Collection<IProject> projects, InfobaseReference infobase)
            throws ToolException {
        List<String> atRisk = new ArrayList<>();
        for (IProject project : projects) {
            Risk risk = riskOf(project, infobase);
            if (risk != null) atRisk.add(risk.item());
        }
        return atRisk;
    }

    /**
     * Пункт {@link #projectsAtRisk} по одному проекту: {@code item} — текст для {@link #atRiskMessage};
     * {@code unverifiedReason} — не {@code null}, если EDT проект проверить не смогла (тогда это не «EDT
     * считает проект несинхронизированным», а «неизвестно»).
     */
    public record Risk(String item, String unverifiedReason) {}

    /**
     * Проект глазами EDT: {@code null} — совпадает с базой; иначе {@link Risk}. Сбой самой проверки
     * ({@link RuntimeException}, {@link LinkageError}) — тоже риск, с причиной в {@code unverifiedReason}.
     */
    public Risk riskOf(IProject project, InfobaseReference infobase) throws ToolException {
        Object sm = stateManager();
        if (sm == null) throw new ToolException(UNAVAILABLE);
        IInfobaseSynchronizationStateManager manager = (IInfobaseSynchronizationStateManager) sm;
        try {
            if (!manager.hasSynchronizationInfo(project, infobase)) {
                return new Risk(project.getName() + " (проект ещё не синхронизировался с этой базой: у EDT нет "
                    + "сведений, что в нём совпадает с базой)", null);
            }
            if (manager.isProjectDirty(project, infobase)) {
                return new Risk(project.getName() + " (есть правки, не залитые в базу)", null);
            }
            return null;
        } catch (RuntimeException | LinkageError e) {
            String reason = McpJobs.describe(e);
            return new Risk(project.getName() + " (не удалось проверить: " + reason + ")", reason);
        }
    }

    /**
     * Записанный {@code ConfigDumpInfo} пары, отложенный для полной перезагрузки (fix round 9, рекомендация 1):
     * {@code original} — где его ищет EDT, {@code aside} — куда он перенесён (рядом, {@code <имя>.mcp-prev}). После
     * забора — {@link #settleDumpInfo}: полная перезагрузка была — копия удаляется, не было — возвращается. Только
     * типы JDK.
     */
    public record DumpInfoMove(Path original, Path aside) {}

    /** Чем кончилась судьба отложенного {@code ConfigDumpInfo} ({@link #settleDumpInfo}). */
    public enum DumpInfoFate {
        /** Ничего не откладывалось. */
        NONE,
        /** Полная перезагрузка была — отложенная копия удалена. */
        DISCARDED,
        /** Полной перезагрузки не было — копия возвращена на место: записанное состояние прежнее. */
        RESTORED,
        /** Пока шёл забор, EDT записала свой {@code ConfigDumpInfo} — оставлен её, копия удалена. */
        KEPT_EDT_COPY,
        /** Вернуть копию не удалось — у пары нет записанного состояния, EDT считает её ни разу не синхронизированной. */
        RESTORE_FAILED
    }

    /**
     * Итог {@link #settleDumpInfo}: {@code reason} — у {@code RESTORE_FAILED} почему не удалось вернуть; у
     * {@code RESTORED}/{@code KEPT_EDT_COPY} — замечание, если после этого не снялся замок EDT (иначе {@code null}).
     */
    public record DumpInfoSettlement(DumpInfoFate fate, String reason) {
        static final DumpInfoSettlement NONE = new DumpInfoSettlement(DumpInfoFate.NONE, null);
    }

    /**
     * Итог {@link #forceRecheck}: {@code done} — ближайшее обновление проекта из базы сравнит базу с записанным
     * состоянием (а с {@code fullReload} — выгрузит конфигурацию целиком); иначе {@code reason} — почему нет.
     * {@code moved} — записанный {@code ConfigDumpInfo} пары отложен для полной перезагрузки (fix round 9: не
     * удалён, а перенесён рядом — {@link #settleDumpInfo} вернёт его, если полной перезагрузки не было), даже если
     * что-то после переноса (снятие замка) не удалось и {@code done = false} (M2); {@code null} — ничего не
     * откладывалось (в том числе когда файла и не было, m3). Только свои типы и JDK.
     */
    public record Recheck(boolean done, String reason, DumpInfoMove moved) {

        /** Без переноса {@code ConfigDumpInfo}. */
        public Recheck(boolean done, String reason) {
            this(done, reason, null);
        }

        /** {@code ConfigDumpInfo} пары отложен: до {@link #settleDumpInfo} EDT считает пару ни разу не синхронизированной. */
        public boolean dumpInfoMoved() {
            return moved != null;
        }

        static Recheck ok() {
            return new Recheck(true, null, null);
        }

        static Recheck ok(DumpInfoMove moved) {
            return new Recheck(true, null, moved);
        }

        static Recheck failed(String reason) {
            return new Recheck(false, reason, null);
        }

        static Recheck failed(String reason, DumpInfoMove moved) {
            return new Recheck(false, reason, moved);
        }
    }

    /**
     * Судьба отложенного {@code ConfigDumpInfo} после забора (fix round 9, рекомендация 1): полная перезагрузка была
     * ({@code Retrieval.fullReload()}) — копия удаляется; не было (NO_CHANGES, сбой, отмена, инкрементальный забор) —
     * возвращается на место под теми же замками EDT, что и перенос ({@link EdtSyncStateRecheck#restore}), если EDT
     * тем временем не записала свой (тогда остаётся её, наша копия удаляется). Ничего не откладывалось —
     * {@link DumpInfoFate#NONE}. Не бросает: сбой — {@link DumpInfoFate#RESTORE_FAILED} с причиной.
     */
    public DumpInfoSettlement settleDumpInfo(IProject project, InfobaseReference infobase, Recheck recheck,
                                             boolean fullReloadDone) {
        if (recheck == null || recheck.moved() == null) return DumpInfoSettlement.NONE;
        DumpInfoMove moved = recheck.moved();
        if (fullReloadDone) {
            try {
                Files.deleteIfExists(moved.aside());
            } catch (IOException | RuntimeException e) {
                // Лишняя копия рядом с хранилищем EDT ничему не мешает: EDT её не читает.
            }
            return new DumpInfoSettlement(DumpInfoFate.DISCARDED, null);
        }
        Object sm = stateManager();
        if (sm == null) {
            return new DumpInfoSettlement(DumpInfoFate.RESTORE_FAILED, "сервис синхронизации EDT недоступен");
        }
        Object delegate;
        try {
            delegate = delegateOf(sm);
        } catch (ToolException e) {
            return new DumpInfoSettlement(DumpInfoFate.RESTORE_FAILED, e.getMessage());
        }
        return EdtSyncStateRecheck.restore(delegate, project, infobase, moved);
    }

    /** Внутренний делегат сервиса v2 ({@code getDelegate()}, рефлексией); не отдал — {@link ToolException} с причиной. */
    private static Object delegateOf(Object sm) throws ToolException {
        Object delegate;
        try {
            delegate = sm.getClass().getMethod("getDelegate").invoke(sm);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ToolException("сервис синхронизации EDT не отдал делегат: " + McpJobs.describe(cause));
        } catch (NoSuchMethodException e) {
            throw new ToolException("в этой версии EDT нет внутреннего метода " + e.getMessage());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new ToolException("внутренний API синхронизации EDT недоступен: " + McpJobs.describe(e));
        }
        if (delegate == null) {
            throw new ToolException("сервис синхронизации EDT не отдал делегат (getDelegate() вернул null)");
        }
        return delegate;
    }

    /** Сколько ждать чужую синхронизацию базы перед закрытием сеанса агента, сбросом и отметкой (fix round 8). */
    public static final Duration FLOW_WAIT = Duration.ofMinutes(2);

    /** Шаг спящего опроса {@code isFlowActive}. */
    static final Duration FLOW_POLL = Duration.ofMillis(500);

    /**
     * Ждёт, пока с базой не идёт синхронизация ({@code isFlowActive}), — вместо отказа «другая синхронизация
     * идёт прямо сейчас» (живой прогон 1.24.0, L9: фоновая проверка проекта EDT после запуска держала поток, и
     * отметка не прошла). Спящий опрос ({@code Thread.sleep} шагом {@link #FLOW_POLL}), не холостой цикл.
     * Как и сам {@code isFlowActive}, видит только синхронизации своего процесса EDT.
     *
     * @return {@code null} — синхронизации нет; иначе причина: «EDT синхронизирует эту базу дольше N с» или —
     *     fail-closed (fix round 9, m4) — «не удалось проверить, идёт ли синхронизация базы: …», если спросить не
     *     удалось: сеанс агента тогда не закрывают, сброс не делают, отметку не ставят
     * @throws OperationCanceledException задание отменено (или поток прерван) во время ожидания
     */
    public String awaitNoActiveFlow(InfobaseReference infobase, IProgressMonitor monitor, Duration max) {
        return awaitNoActiveFlow(infobase, monitor, max, FLOW_POLL);
    }

    /** @param poll шаг опроса (тесты — короче) */
    public String awaitNoActiveFlow(InfobaseReference infobase, IProgressMonitor monitor, Duration max,
                                    Duration poll) {
        Object sm = stateManager();
        if (sm == null) return null;
        IInfobaseSynchronizationStateManager manager = (IInfobaseSynchronizationStateManager) sm;
        long deadline = System.nanoTime() + max.toNanos();
        while (true) {
            boolean active;
            try {
                active = manager.isFlowActive(infobase);
            } catch (RuntimeException | LinkageError e) {
                return "не удалось проверить, идёт ли синхронизация базы: " + McpJobs.describe(e);
            }
            if (!active) return null;
            if (monitor != null && monitor.isCanceled()) {
                throw new OperationCanceledException("ожидание синхронизации базы " + infobase.getName());
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                return "EDT синхронизирует эту базу дольше " + Math.max(1, (max.toMillis() + 999) / 1000) + " с";
            }
            try {
                Thread.sleep(Math.max(1, Math.min(poll.toMillis(), remainingMs)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OperationCanceledException("ожидание синхронизации базы " + infobase.getName()
                    + " прервано");
            }
        }
    }

    /**
     * Сбрасывает быструю проверку EDT «база не менялась» для пары проект — база: ближайшее обновление проекта из
     * базы сравнит базу с записанным состоянием, а не поверит идентификатору поколения данных (если открыт и шлюз
     * соединения EDT — {@link ThickClientOps#releaseDesignerSession}: иначе EDT ответит «изменений нет», не спросив
     * базу вовсе, m4); с {@code fullReload} — ещё и отложит записанный {@code ConfigDumpInfo} пары (перенос рядом,
     * fix round 9; вернуть или выбросить — {@link #settleDumpInfo}), и EDT выгрузит конфигурацию целиком
     * ({@code fullReload = true} слияния).
     *
     * <p><b>Зачем (живой прогон 1.24.0, F4).</b> Забирая изменения базы, EDT первым делом спрашивает у агента
     * конфигуратора идентификатор поколения данных базы и, если он равен записанному, отвечает {@code NO_CHANGES},
     * вовсе не сравнивая конфигурацию. Загрузка {@code .dt} (наш сценарий 3 и мастер восстановления в IDE) и
     * {@code .cfe} состояние синхронизации не трогают, а идентификатор поколения у разных баз из наших {@code .dt}
     * совпадал: после {@code restore_infobase_from_dt} проект молча оставался прежним, а
     * {@code discardProjectChanges} при неизменной базе ничего не отбрасывал. Пустой идентификатор — «неизвестно»
     * самой EDT: с ним быстрая проверка всегда говорит «изменилось». Публичного API нет — рефлексия по делегату
     * ({@link EdtSyncStateRecheck}, шаг в шаг как {@code forceEdtSynchronization}).
     *
     * <p>Не трогаем EDT, когда идёт синхронизация с этой базой ({@code isFlowActive}; как у
     * {@link #markSynchronized} — видит только синхронизации своего процесса EDT). Отсутствие состояния
     * синхронизации — не отказ: сбрасывать просто нечего. Любой сбой — {@code Recheck(false, причина)}, не исключение.
     */
    public Recheck forceRecheck(IProject project, InfobaseReference infobase, boolean fullReload) {
        Object sm = stateManager();
        if (sm == null) return Recheck.failed("сервис синхронизации EDT недоступен");
        IInfobaseSynchronizationStateManager manager = (IInfobaseSynchronizationStateManager) sm;
        try {
            if (manager.isFlowActive(infobase)) {
                return Recheck.failed("другая синхронизация с этой базой идёт прямо сейчас");
            }
        } catch (RuntimeException | LinkageError e) {
            return Recheck.failed("не удалось проверить состояние синхронизации: " + McpJobs.describe(e));
        }
        Object delegate;
        try {
            delegate = delegateOf(sm);
        } catch (ToolException e) {
            return Recheck.failed(e.getMessage());
        }
        return EdtSyncStateRecheck.run(delegate, project, infobase, fullReload);
    }

    /**
     * Итог {@link #markSynchronized}: {@code marked} — EDT записала текущее состояние проекта как
     * совпадающее с базой; иначе {@code reason} — почему нет (для текста предупреждения). Только типы
     * JDK: запись стоит в сигнатуре метода класса, который биндится в Guice.
     */
    public record Marking(boolean marked, String reason) {
        static Marking done() {
            return new Marking(true, null);
        }

        static Marking failed(String reason) {
            return new Marking(false, reason);
        }
    }

    /**
     * Записывает текущее состояние проекта как совпадающее с базой — то же, что EDT делает сама после
     * заливки проекта в базу.
     *
     * <p><b>Зачем (живой прогон 1.24.0, L8).</b> Забрав изменения базы в проект, EDT 2026.1 записывает
     * подтянутые ресурсы с пустой подписью ({@code UpdateProjectFlow$UpdateProjectFlowResultEdtResourcesMetadataComputer
     * .handleInfobaseChanges} → {@code new EdtResourceMetadata(null, uuid)}), а
     * {@code finishSynchronizationFlow} сбрасывает отметку времени проверки. Следующий
     * {@code isProjectDirty} сверяет настоящие подписи с записанными, пустая с настоящей не совпадает —
     * и проект «грязный», пока его не зальют в базу. Так EDT ведёт себя и в IDE; пустые подписи в EDT
     * ничто не заменяет. Публичного API «отметить синхронизированным» нет, а внутренний есть:
     * {@code InfobaseSynchronizationStateManager.getDelegate()} →
     * {@code InfobaseSynchronizationStateManagerDelegate.forceEdtSynchronization(InfobaseReference, IProject)}
     * (оба публичные; {@code forceEdtSynchronization} сама EDT нигде не вызывает). Он очищает
     * записанные подписи пары проект — база, кладёт текущие
     * ({@code IResourceStoreManager.getEffectiveResourceMetadata}), пишет хранилище состояния и
     * сбрасывает отметку времени; для проекта расширения делегат сам берёт состояние расширения.
     *
     * <p><b>Только рефлексией.</b> Классы внутренние: ни статической ссылки на них, ни упоминания в
     * сигнатурах (класс биндится в Guice). Любой сбой — нет метода (другая версия EDT), нет доступа,
     * исключение EDT — не бросается, а возвращается {@code Marking.failed(причина)}: отметка —
     * улучшение, обновление проекта состоялось и без неё.
     *
     * <p>Вызывающий отвечает за то, что содержимое проекта действительно совпадает с базой, и за то,
     * что модель EDT уже обработала изменённые файлы ({@code IBmModelManager.waitModelSynchronization}):
     * записываются подписи, которые EDT видит в момент вызова.
     *
     * <p><b>Когда EDT не трогаем</b> (fix round 4, по байткоду делегата):
     * <ul>
     * <li>нет состояния синхронизации проекта с базой ({@code hasSynchronizationInfo = false}) — например,
     *   проект расширения, у родителя которого состояния нет: {@code calculateStoreProject} вернёт сам
     *   проект, и делегат записал бы общий статический {@code InfobaseSyncState.UNDEFINED};</li>
     * <li>идёт синхронизация с этой базой ({@code isFlowActive}): {@code lockInfobaseState}/
     *   {@code unlockInfobaseState} внутри {@code forceEdtSynchronization} владельца не проверяют и могли бы
     *   снять замок чужого потока.</li>
     * </ul>
     * Окно «проверил — вызвал» эти проверки не закрывают. И {@code isFlowActive} видит только синхронизации
     * <b>этого</b> процесса EDT (fix round 5, M3, по байткоду делегата): если держатель состояния базы в памяти
     * уже есть — а после обновления проекта он есть, — ответ берётся из него, межпроцессный замок
     * ({@code MemoryMappedLock.checkLock}) не проверяется; синхронизацию той же базы другим экземпляром EDT
     * проверка не заметит.
     */
    public Marking markSynchronized(IProject project, InfobaseReference infobase) {
        Object sm = stateManager();
        if (sm == null) return Marking.failed("сервис синхронизации EDT недоступен");
        IInfobaseSynchronizationStateManager manager = (IInfobaseSynchronizationStateManager) sm;
        try {
            if (!manager.hasSynchronizationInfo(project, infobase)) {
                return Marking.failed("EDT не записала состояние синхронизации проекта с этой базой (для проекта "
                    + "расширения — и его родителя)");
            }
            if (manager.isFlowActive(infobase)) {
                return Marking.failed("другая синхронизация с этой базой идёт прямо сейчас");
            }
        } catch (RuntimeException | LinkageError e) {
            return Marking.failed("не удалось проверить состояние синхронизации: " + McpJobs.describe(e));
        }
        try {
            Object delegate = sm.getClass().getMethod("getDelegate").invoke(sm);
            if (delegate == null) {
                return Marking.failed("сервис синхронизации EDT не отдал делегат (getDelegate() вернул null)");
            }
            delegate.getClass().getMethod("forceEdtSynchronization", InfobaseReference.class, IProject.class)
                .invoke(delegate, infobase, project);
            return Marking.done();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Marking.failed("EDT не записала состояние синхронизации: " + McpJobs.describe(cause));
        } catch (NoSuchMethodException e) {
            return Marking.failed("в этой версии EDT нет внутреннего метода " + e.getMessage());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Marking.failed("внутренний API синхронизации EDT недоступен: " + McpJobs.describe(e));
        }
    }

    /**
     * Текст отказа для непустого {@link #projectsAtRisk}. Один безопасный текст на оба случая:
     * первым идёт {@code discardProjectChanges} для проекта, чьё содержимое не нужно, а
     * {@code deploy_project} — только для проекта с нужными правками и с прямым предупреждением:
     * он заменяет конфигурацию базы содержимым проекта, и для пустого проекта это разрушительно.
     */
    public static String atRiskMessage(List<String> atRisk) {
        return "содержимое проектов будет заменено версией из базы: " + String.join("; ", atRisk)
            + ". Если содержимое этих проектов не нужно — проект только что создан, пуст или всё нужное "
            + "уже есть в базе, — повторите с discardProjectChanges: true. Если в проекте есть нужные "
            + "правки — сначала залейте их в базу (deploy_project). Внимание: deploy_project заменяет "
            + "конфигурацию базы содержимым проекта — для пустого или только что созданного проекта его "
            + "не вызывайте.";
    }

    /**
     * Текст отказа обновить проект, когда задание УЖЕ загрузило в базу {@code .dt} или {@code .cfe} (сценарии 2 и 3,
     * fix round 6b). Общий {@link #atRiskMessage} здесь вреден: его совет «сначала залейте правки (deploy_project)»
     * заменил бы только что загруженную конфигурацию базы прежним содержимым проекта. Поэтому: загрузка выполнена,
     * проект не обновлён и не тронут, {@code deploy_project} сейчас не вызывать, выход — сохранить нужные правки
     * отдельно и забрать конфигурацию базы с {@code discardProjectChanges: true}.
     *
     * @param loadDone что уже сделано с базой — законченное придаточное с маленькой буквы, например «файл .dt «x.dt»
     *     уже загружен в базу Y»
     * @param atRisk пункты «проект (почему)», как у {@link #atRiskMessage}
     */
    public static String atRiskAfterLoadMessage(String loadDone, List<String> atRisk) {
        return loadDone + ", но проект из базы не обновлён: EDT не подтвердила, что в нём нет изменений, которых нет в "
            + "базе: " + String.join("; ", atRisk) + ". Содержимое проекта не тронуто. Не вызывайте сейчас "
            + "deploy_project: он заменит только что загруженную конфигурацию базы содержимым проекта. Нужные правки "
            + "сохраните отдельно (например, в git), затем заберите конфигурацию базы в проект: "
            + "update_project_from_infobase с discardProjectChanges: true.";
    }

    /**
     * Текст отказа сценариев 2 и 3 ДО загрузки (fix round 9, I2): при вызове и в шаге {@code check-projects}. Совет
     * общего {@link #atRiskMessage} «сначала залейте правки (deploy_project)» здесь бесполезен и вреден: задание
     * загрузит {@code .dt} (всю конфигурацию) или {@code .cfe} (расширение) поверх залитого, а затем заменит и проекты
     * версией из базы — правки потеряются. Поэтому: что заменит загрузка, какие проекты с правками, сохранить правки
     * отдельно (git; для {@code .dt} — ещё {@code backupTo}, чтобы сохранить и текущую базу), повторить с флагом.
     *
     * @param loadWillReplace что заменит загрузка — придаточное с маленькой буквы, например «загрузка .dt «x.dt»
     *     заменит всю конфигурацию и данные базы Y»
     * @param atRisk пункты «проект (почему)», как у {@link #atRiskMessage}
     * @param offerBackup предложить {@code backupTo} (сценарий 3 без резервной выгрузки)
     */
    public static String atRiskBeforeLoadMessage(String loadWillReplace, List<String> atRisk, boolean offerBackup) {
        return loadWillReplace + ", а затем и проекты — версией из базы, но EDT не подтвердила, что в них нет "
            + "изменений, которых нет в базе: " + String.join("; ", atRisk) + ". Нужные правки сохраните отдельно "
            + "(например, в git)" + (offerBackup ? "; чтобы сохранить и текущую базу, передайте backupTo" : "")
            + ", затем повторите с discardProjectChanges: true. Ничего не тронуто.";
    }

    private static boolean isStateManager(Object candidate) {
        try {
            return candidate instanceof IInfobaseSynchronizationStateManager;
        } catch (LinkageError e) {
            return false;
        }
    }

    /**
     * Защита от рассинхронизации формы обратного вызова: рантайм-{@code IInfobaseChangesResolver
     * .resolveInfobaseChanges} должен иметь ровно ту форму (10 параметров + возвращаемый тип), под
     * которую написан {@code HeadlessInfobaseChangesResolver}. Несовпадение (в т.ч. отсутствие
     * самого класса — старый EDT) — {@code false}, тем же путём, что и обычная недоступность API;
     * без этой проверки расхождение формы всплыло бы не здесь, а {@code AbstractMethodError} прямо
     * во время живой синхронизации. class-литералы v2-типов — только внутри тела этого метода
     * (не в полях/сигнатурах SyncV2), поэтому безопасны — см. JavaDoc класса.
     */
    private static boolean matchesExpectedResolverShape() {
        try {
            Method m = IInfobaseChangesResolver.class.getMethod("resolveInfobaseChanges",
                IProject.class, InfobaseReference.class, Set.class, Set.class, Set.class,
                IInfobaseConfigurationChange.class, IInfobaseUpdateConflictResolver.class,
                IInfobaseUpdateConflictResolver.IConflictResolveAssist.class,
                IInfobaseSynchronizationFlow.class, IProgressMonitor.class);
            return m.getReturnType() == InfobaseConflictResolution.class;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }
}
