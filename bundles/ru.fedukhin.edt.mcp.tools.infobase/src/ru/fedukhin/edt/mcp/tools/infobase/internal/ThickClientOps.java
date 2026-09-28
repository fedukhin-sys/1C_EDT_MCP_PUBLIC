package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IDesignerSessionThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import jakarta.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Операции конфигуратора над базой через исполнитель 1C:EDT — тем же путём, что мастер IDE
 * «Загрузить информационную базу» ({@code RestoreInfobaseWizard}).
 *
 * <p><b>Почему не свой 1cv8.</b> Для платформы 8.3.10+ EDT регистрирует исполнителем толстого
 * клиента {@code DesignerSessionThickClientLauncher}: операции идут через её агент конфигуратора
 * ({@code 1cv8 DESIGNER /AgentMode}), который держит базу монопольно. Свой пакетный 1cv8 рядом
 * с живым агентом молча висит; исполнитель EDT использует агент, а если сессии в пуле нет — сам
 * запускает пакетный конфигуратор.
 *
 * <p><b>Исключение — загрузка {@code .cfe}</b> ({@link #loadExtension}): исполнитель EDT грузит файл
 * как основную конфигурацию, поэтому там — свой пакетный конфигуратор ({@link DesignerBatch}) после
 * закрытия сеанса агента EDT на базе.
 */
public class ThickClientOps {

    /** Компонент толстого клиента и его исполнитель для конкретной базы. */
    public record Launcher(ILaunchableRuntimeComponent component, IThickClientLauncher launcher) {}

    /** Резолв исполнителя — seam для тестов. */
    public interface LauncherResolver {
        Launcher resolve(IProject project, InfobaseReference infobase) throws ToolException;
    }

    @FunctionalInterface
    interface Operation<T> {
        T run(Launcher launcher, RuntimeExecutionArguments args) throws RuntimeExecutionException, ToolException;
    }

    static final String SESSIONS_HINT = ". Если базу держат клиентские сеансы или отладка (в том числе из "
        + "других инстанций EDT), завершите их: list_running_clients includeForeign: true";

    static final Duration DEFAULT_LOCK_POLL = Duration.ofSeconds(5);

    /** Предел пакетной загрузки {@code .cfe}: загрузка расширения — минуты, не часы. */
    static final Duration CFE_LOAD_TIMEOUT = Duration.ofMinutes(60);

    /**
     * Разбор «Дополнительных параметров» базы — тем же шаблоном, что у исполнителя EDT
     * ({@code AbstractExecutionCommandBuilder.ADDITIONAL_PARAMETER_SPLITTER}).
     */
    private static final Pattern ADDITIONAL_PARAMETER = Pattern.compile("([^\"]\\S*|\".+?\")\\s*");

    private final LauncherResolver resolver;
    private final IInfobaseAccessManager accessManager;
    private final IInfobaseManager infobaseManager;
    private final Duration lockPoll;
    private final DesignerBatch batch;
    private final Duration cfeLoadTimeout;

    @Inject
    public ThickClientOps(IResolvableRuntimeInstallationManager installations, IRuntimeComponentManager components,
                          IInfobaseAccessManager accessManager, IInfobaseManager infobaseManager) {
        this(new DefaultLauncherResolver(installations, components), accessManager, infobaseManager);
    }

    public ThickClientOps(LauncherResolver resolver, IInfobaseAccessManager accessManager,
                          IInfobaseManager infobaseManager) {
        this(resolver, accessManager, infobaseManager, DEFAULT_LOCK_POLL);
    }

    /**
     * @param lockPoll как часто, ожидая внутрипроцессный замок базы, проверять отмену задания
     *     (по умолчанию {@link #DEFAULT_LOCK_POLL}); тесты задают короче. Не {@code null} и не меньше
     *     1 мс: {@code tryLock(0)} в цикле ожидания был бы холостым циклом
     */
    public ThickClientOps(LauncherResolver resolver, IInfobaseAccessManager accessManager,
                          IInfobaseManager infobaseManager, Duration lockPoll) {
        this(resolver, accessManager, infobaseManager, lockPoll, new DesignerBatch());
    }

    /** @param batch пакетный конфигуратор для загрузки {@code .cfe} (тесты подставляют процесс-заглушку) */
    public ThickClientOps(LauncherResolver resolver, IInfobaseAccessManager accessManager,
                          IInfobaseManager infobaseManager, Duration lockPoll, DesignerBatch batch) {
        this(resolver, accessManager, infobaseManager, lockPoll, batch, CFE_LOAD_TIMEOUT);
    }

    /**
     * @param cfeLoadTimeout предел пакетной загрузки {@code .cfe} (по умолчанию {@link #CFE_LOAD_TIMEOUT}):
     *     зависший 1cv8 рядом с незакрытым агентом не должен держать замок базы EDT до лимита всего
     *     задания; не меньше 1 мс
     */
    public ThickClientOps(LauncherResolver resolver, IInfobaseAccessManager accessManager,
                          IInfobaseManager infobaseManager, Duration lockPoll, DesignerBatch batch,
                          Duration cfeLoadTimeout) {
        this.resolver = resolver;
        this.accessManager = accessManager;
        this.infobaseManager = infobaseManager;
        this.lockPoll = DesignerBatch.requireAtLeastOneMillisecond(lockPoll, "lockPoll");
        this.batch = Objects.requireNonNull(batch, "batch");
        this.cfeLoadTimeout = DesignerBatch.requireAtLeastOneMillisecond(cfeLoadTimeout, "cfeLoadTimeout");
    }

    /** Загружает {@code .dt} в базу. Все данные базы заменяются содержимым файла. */
    public void restoreDt(IProject project, InfobaseReference infobase, Path dtFile, IProgressMonitor monitor)
            throws ToolException {
        run("загрузка .dt в базу " + infobase.getName(), true, project, infobase, monitor, null, (l, args) -> {
            l.launcher().importDtToInfobase(l.component(), infobase, args, dtFile);
            return null;
        });
    }

    /** Выгружает базу в {@code .dt}. */
    public void backupDt(IProject project, InfobaseReference infobase, Path dtFile, IProgressMonitor monitor)
            throws ToolException {
        run("выгрузка базы " + infobase.getName() + " в .dt", false, project, infobase, monitor, null, (l, args) -> {
            l.launcher().exportDtFromInfobase(l.component(), infobase, args, dtFile);
            return null;
        });
    }

    /**
     * Загружает {@code .cfe} в расширение базы: существующее заменяется, отсутствующее создаётся.
     *
     * <p><b>Не через исполнитель EDT.</b> {@code importCfToInfobase} EDT — и агентный
     * ({@code DesignerSessionThickClientLauncher}), и пакетный ({@code ThickClientLauncher}) — строит
     * {@code /LoadCfg <файл>} без {@code -Extension}: имя расширения из аргументов не передаётся, и файл
     * грузится как ОСНОВНАЯ конфигурация. Живой прогон 1.24.0: «Ожидается файл конфигурации», а
     * {@code .cf}, переименованный в {@code .cfe}, заменил бы конфигурацию базы. Поэтому — пакетный
     * конфигуратор той установки, что EDT выбрала для базы ({@code component.getFile()}), с тем, что
     * исполнитель EDT добавляет к каждой операции: {@code DESIGNER <база> /LoadCfg <cfe> -Extension <имя>
     * [<дополнительные параметры базы>] [/WA - /N <пользователь> [/P <пароль>] | /WA +]}. Сначала
     * собирается команда (неподдерживаемая база откажет, не тронув агент), затем закрывается сеанс агента
     * конфигуратора EDT на базе — база нужна монопольно; агент EDT откроет заново при следующей операции
     * (применение расширения идёт уже через него). Всё — под тем же внутрипроцессным замком базы, что и
     * остальные операции; у самого конфигуратора свой предел времени ({@link #CFE_LOAD_TIMEOUT}).
     */
    public void loadExtension(IProject project, InfobaseReference infobase, Path cfeFile, String extensionName,
                              IProgressMonitor monitor) throws ToolException {
        String what = "загрузка расширения " + extensionName + " из " + cfeFile.getFileName();
        run(what, true, project, infobase, monitor, extensionName, (l, args) -> {
            List<String> arguments = new ArrayList<>(DesignerBatch.connection(infobase));
            arguments.addAll(List.of("/LoadCfg", cfeFile.toString(), "-Extension", extensionName));
            arguments.addAll(additionalParameters(infobase));
            appendAccess(arguments, args);
            File executable = l.component().getFile();
            if (executable == null) {
                throw new ToolException("ошибка: " + what + " — не найден исполняемый файл конфигуратора 1cv8 "
                    + "для базы " + infobase.getName());
            }
            String closeFailure = closeDesignerSession(l, infobase, args);
            String closeNote = closeFailure == null ? ""
                : " (сеанс агента конфигуратора EDT закрыть не удалось: "
                    + maskPassword(closeFailure, args.getPassword()) + ")";
            DesignerBatch.Result result;
            try {
                result = batch.run(executable, arguments, monitor, cfeLoadTimeout);
            } catch (ToolException e) {
                throw new ToolException("ошибка: " + what + " — " + maskPassword(e.getMessage(), args.getPassword())
                    + closeNote + SESSIONS_HINT);
            }
            if (result.exitCode() != 0) {
                String reason = maskPassword(result.output(), args.getPassword());
                throw new ToolException("ошибка: " + what + " — конфигуратор завершился с кодом " + result.exitCode()
                    + (reason.isEmpty() ? "" : ": " + reason) + closeNote + SESSIONS_HINT);
            }
            return null;
        });
    }

    /**
     * «Дополнительные параметры» базы (код разрешения {@code /UC…}, разделители {@code /Z…} и т. п.) —
     * как их передаёт исполнитель EDT ({@code AbstractRuntimeComponentExecutor.appendAdditionalParameters}):
     * у зарегистрированной базы (по UUID; не нашлась — у переданной), разбор тем же шаблоном, кавычки
     * снимаются, каждый параметр — отдельным элементом команды. Без них база с кодом разрешения не пустит
     * конфигуратор, а разделённая загрузит расширение не в своём контексте.
     */
    private List<String> additionalParameters(InfobaseReference infobase) {
        String raw;
        try {
            InfobaseReference registered = infobase.getUuid() == null ? infobase
                : infobaseManager.findInfobaseByUuid(infobase.getUuid()).orElse(infobase);
            raw = registered.getAdditionalParameters();
        } catch (RuntimeException | LinkageError e) {
            // Параметров нет или модель EDT без них — как у базы без дополнительных параметров.
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        Matcher m = ADDITIONAL_PARAMETER.matcher(raw);
        while (m.find()) {
            String parameter = m.group(1).replace("\"", "").trim();
            if (!parameter.isEmpty()) out.add(parameter);
        }
        return out;
    }

    /**
     * Доступ к базе — как у исполнителя EDT ({@code appendInfobaseAccess}): учётная запись ОС —
     * {@code /WA +}; пользователь ИБ — {@code /WA -}, затем {@code /N}, {@code /P}; способ не задан —
     * ничего. Всё отдельными элементами (см. {@link DesignerBatch}).
     */
    private static void appendAccess(List<String> command, RuntimeExecutionArguments args) {
        if (args.getAccess() == InfobaseAccess.OS) {
            command.add("/WA");
            command.add("+");
        } else if (args.getAccess() == InfobaseAccess.INFOBASE) {
            command.add("/WA");
            command.add("-");
            DesignerBatch.appendCredentials(command, args.getUsername(), args.getPassword());
        }
    }

    /**
     * Закрывает сеанс агента конфигуратора EDT на базе: пакетный 1cv8 рядом с живым агентом, который
     * держит базу монопольно, не загрузит расширение; перед забором изменений базы это же открывает «шлюз»
     * EDT ({@link #releaseDesignerSession}). Исполнитель без агента (платформа до 8.3.10) — закрывать нечего.
     * Не удалось — не останавливаемся: при загрузке держит ли агент базу, скажет отказ самого пакетного
     * конфигуратора, а текст сбоя закрытия попадёт в ошибку.
     *
     * @return текст сбоя закрытия (пароль ещё не вырезан) или {@code null}
     */
    private static String closeDesignerSession(Launcher l, InfobaseReference infobase,
                                               RuntimeExecutionArguments args) {
        try {
            if (l.launcher() instanceof IDesignerSessionThickClientLauncher session) {
                session.closeDesignerSession(l.component(), infobase, args);
            }
            return null;
        } catch (OperationCanceledException e) {
            // Отмена задания (fix round 8) — не «причина»: пусть задание остановится.
            throw e;
        } catch (RuntimeExecutionException | RuntimeException | LinkageError e) {
            return message(e);
        }
    }

    /**
     * Открывает «шлюз» EDT перед забором изменений базы (fix round 7, R7-1): закрывает сеанс агента конфигуратора
     * EDT на базе публичным {@code IDesignerSessionThickClientLauncher.closeDesignerSession} — как перед загрузкой
     * {@code .cfe} ({@link #loadExtension}): тем же исполнителем, с теми же аргументами доступа, под тем же
     * внутрипроцессным замком базы.
     *
     * <p><b>Зачем.</b> Соединение EDT с базой ({@code DesignerSessionInfobaseConnection}) после каждого забора
     * ставит себе {@code externalChangesCheckRequired = false} и, пока так, отвечает {@code NO_CHANGES}, вовсе не
     * спрашивая базу; обратно в {@code true} его возвращает только закрытие сеанса агента (слушатель
     * {@code closed()} — синхронно, при освобождении соединения агента). EDT откроет сеанс сама при следующей
     * операции. Исполнитель без агента (платформа до 8.3.10) — закрывать нечего, и такого шлюза у EDT для него
     * нет: успех.
     *
     * @return причина, по которой сеанс закрыть не удалось (без пароля ИБ), или {@code null} — закрытие прошло без
     *     ошибки. Что шлюз EDT после этого действительно открыт, {@code null} не доказывает (fix round 8, M1; fix
     *     round 9, m4): флаг соединения снаружи не виден, и «изменений нет» после закрытия без ошибки остаётся
     *     аномалией, а не подтверждением. Сбои EDT и исполнителя не бросаются; отмена задания
     *     ({@link OperationCanceledException}) — бросается
     */
    public String releaseDesignerSession(IProject project, InfobaseReference infobase, IProgressMonitor monitor) {
        try {
            return run("закрытие сеанса агента конфигуратора EDT на базе " + infobase.getName(), false, project,
                infobase, monitor, null, (l, args) -> {
                    String failure = closeDesignerSession(l, infobase, args);
                    return failure == null ? null : maskPassword(failure, args.getPassword());
                });
        } catch (ToolException e) {
            return e.getMessage();
        } catch (OperationCanceledException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            return message(e);
        }
    }

    /** Применяет расширение к базе данных; реструктуризация подтверждается автоматически. */
    public void applyExtension(IProject project, InfobaseReference infobase, String extensionName,
                               IProgressMonitor monitor) throws ToolException {
        Boolean applied = run("применение расширения " + extensionName, true, project, infobase, monitor,
            extensionName,
            (l, args) -> l.launcher().updateDatabaseConfiguration(l.component(), infobase, changes -> true, args));
        if (!Boolean.TRUE.equals(applied)) {
            throw new ToolException("расширение " + extensionName + " не применено к базе " + infobase.getName()
                + ": конфигуратор отказал без описания причины" + SESSIONS_HINT);
        }
    }

    /** Имена расширений, загруженных в базу. */
    public List<String> listExtensions(IProject project, InfobaseReference infobase, IProgressMonitor monitor)
            throws ToolException {
        List<String> names = run("получение списка расширений базы " + infobase.getName(), false, project,
            infobase, monitor, null,
            (l, args) -> l.launcher().listConfigurationExtensions(l.component(), infobase, args));
        return names == null ? List.of() : List.copyOf(names);
    }

    /**
     * Сохраняет пользователя ИБ в настройки доступа EDT к базе — тем же механизмом пользуется IDE,
     * и дальнейшие операции (в том числе {@code deploy_project}) его подхватывают.
     *
     * <p>Четвёртый аргумент {@code InfobaseAccessSettings} — «Дополнительные параметры» базы
     * (например, {@code /UC…} или разделители {@code /Z…}); IDE при сохранении переносит их из
     * текущих настроек, так же и здесь. Прочитать текущие настройки не удалось — отказ: иначе
     * сохранение учётных данных молча стёрло бы эти параметры.
     */
    public void storeCredentials(InfobaseReference infobase, String user, String password) throws ToolException {
        String additionalProperties = currentAdditionalProperties(infobase);
        try {
            accessManager.updateSettings(infobase, new InfobaseAccessSettings(InfobaseAccess.INFOBASE, user,
                password == null ? "" : password, additionalProperties));
        } catch (CoreException e) {
            throw new ToolException("не удалось сохранить учётные данные базы " + infobase.getName() + ": "
                + e.getMessage(), e);
        }
    }

    /** «Дополнительные параметры» текущих настроек доступа; настроек нет — {@code null}. */
    private String currentAdditionalProperties(InfobaseReference infobase) throws ToolException {
        IInfobaseAccessSettings current;
        try {
            current = accessManager.resolveSettings(infobase);
        } catch (CoreException | RuntimeException e) {
            throw new ToolException("не удалось прочитать текущие настройки доступа к базе " + infobase.getName()
                + " — учётные данные не сохранены, чтобы не потерять дополнительные параметры: " + message(e), e);
        }
        return current == null || current == IInfobaseAccessSettings.NOT_DEFINED ? null
            : current.additionalProperties();
    }

    /**
     * @param exclusive операции нужен монопольный доступ к базе: при отказе к тексту добавляется
     *     подсказка про клиентские сеансы — самую частую причину такого отказа
     */
    private <T> T run(String what, boolean exclusive, IProject project, InfobaseReference infobase,
                      IProgressMonitor monitor, String extensionName, Operation<T> operation) throws ToolException {
        Launcher launcher = resolver.resolve(project, infobase);
        RuntimeExecutionArguments args = arguments(infobase, monitor);
        if (extensionName != null) args.setExtensionName(extensionName);
        // Тот же внутрипроцессный замок базы, под которым работает мастер IDE.
        Lock lock = infobaseManager.getLock(infobase);
        acquire(lock, infobase, monitor);
        try {
            return operation.run(launcher, args);
        } catch (RuntimeExecutionException e) {
            // Платформа иногда эхом вставляет в текст ошибки саму строку подключения — вместе с
            // паролем ИБ, который мы сами передали в args. Он не должен попасть ни в ToolException
            // (текст уходит клиенту — LLM/облако), ни в лог EDT: McpJobs пишет туда исключение шага
            // со всей цепочкой причин. Поэтому причиной идёт не исходное исключение, а его
            // санитизированная копия.
            String maskedMessage = maskPassword(message(e), args.getPassword());
            throw new ToolException("ошибка: " + what + " — " + maskedMessage
                + (exclusive ? SESSIONS_HINT : ""), sanitized(e, maskedMessage));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Берёт внутрипроцессный замок базы, не теряя отмену. {@code lock()} непрерываем: пока замок
     * держит EDT (мастер IDE, синхронизация проекта), не сработали бы ни отмена в Progress view,
     * ни лимит времени, а задание всё это время держало бы межпроцессный замок базы. Поэтому ждём
     * порциями {@link #lockPoll} и между ними проверяем монитор; {@code tryLock} с таймаутом —
     * блокирующее ожидание (поток спит), а не холостой цикл.
     */
    private void acquire(Lock lock, InfobaseReference infobase, IProgressMonitor monitor) {
        try {
            while (!lock.tryLock(lockPoll.toMillis(), TimeUnit.MILLISECONDS)) {
                if (monitor != null && monitor.isCanceled()) {
                    throw new OperationCanceledException("ожидание замка базы " + infobase.getName());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationCanceledException("ожидание замка базы " + infobase.getName() + " прервано");
        }
    }

    /**
     * Значение после ключа {@code /P} командной строки 1cv8 — слитно в кавычках ({@code /P"…"}) или
     * отдельным элементом ({@code /P …}): так 1cv8 может эхом повторить свою команду в тексте отказа.
     */
    private static final Pattern PASSWORD_OPTION =
        Pattern.compile("(?<![\\p{L}\\p{N}])/P(?=[\\s\"])(\\s*)(\"[^\"]*\"|[^\\s\"),;]+)");

    /** Код разрешения {@code /UC…} из «Дополнительных параметров» базы — тоже секрет. */
    private static final Pattern PERMISSION_CODE_OPTION =
        Pattern.compile("(?<![\\p{L}\\p{N}])/UC(\\s*)(\"[^\"]*\"|[^\\s\"),;]+)");

    /**
     * Вырезает пароль ИБ из текста ошибки исполнителя или конфигуратора перед тем, как он попадёт в
     * {@link ToolException}: сначала буквальные вхождения, затем значения ключей {@code /P} и {@code /UC}.
     */
    private static String maskPassword(String text, String password) {
        if (text == null || text.isEmpty()) return text;
        String masked = (password == null || password.isEmpty()) ? text : text.replace(password, "***");
        masked = PASSWORD_OPTION.matcher(masked).replaceAll("/P$1***");
        return PERMISSION_CODE_OPTION.matcher(masked).replaceAll("/UC$1***");
    }

    /**
     * Копия исключения исполнителя для цепочки причин: имя класса и уже замаскированный текст, стек
     * оригинала, без его собственных причин — в них платформа тоже могла вложить строку подключения.
     */
    private static RuntimeException sanitized(Exception e, String maskedMessage) {
        RuntimeException copy = new RuntimeException(e.getClass().getName() + ": " + maskedMessage);
        copy.setStackTrace(e.getStackTrace());
        return copy;
    }

    private RuntimeExecutionArguments arguments(InfobaseReference infobase, IProgressMonitor monitor) {
        RuntimeExecutionArguments args = new RuntimeExecutionArguments();
        args.setMonitor(monitor);
        try {
            IInfobaseAccessSettings settings = accessManager.resolveSettings(infobase);
            if (settings != null && settings != IInfobaseAccessSettings.NOT_DEFINED) {
                if (settings.access() != null) args.setAccess(settings.access());
                args.setUsername(settings.userName());
                args.setPassword(settings.password());
            }
        } catch (CoreException | RuntimeException e) {
            // Сохранённых настроек доступа нет — конфигуратор пойдёт без учётки, что верно для
            // базы без пользователей.
        }
        return args;
    }

    private static String message(Throwable e) {
        String m = e.getLocalizedMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /** Исполнитель толстого клиента для пары «проект — база», как у мастера IDE. */
    public static class DefaultLauncherResolver implements LauncherResolver {

        private final IResolvableRuntimeInstallationManager installations;
        private final IRuntimeComponentManager components;

        public DefaultLauncherResolver(IResolvableRuntimeInstallationManager installations,
                                       IRuntimeComponentManager components) {
            this.installations = installations;
            this.components = components;
        }

        @Override
        public Launcher resolve(IProject project, InfobaseReference infobase) throws ToolException {
            try {
                IResolvableRuntimeInstallation resolvable = installations.resolveByProjectAndInfobase(
                    RuntimeCli.DefaultExecutableResolver.RUNTIME_TYPE_ID, project, infobase, InfobaseAccessType.UPDATE);
                if (resolvable == null) {
                    throw new MatchingRuntimeNotFound("установка не найдена");
                }
                AppArch arch = infobase.getAppArch() == null ? AppArch.AUTO : infobase.getAppArch();
                RuntimeInstallation installation = resolvable.resolve(
                    List.of(IRuntimeComponentTypes.THICK_CLIENT), arch);
                ComponentExecutorInfo<ILaunchableRuntimeComponent, IThickClientLauncher> info =
                    components.resolveExecutor(ILaunchableRuntimeComponent.class, IThickClientLauncher.class,
                        installation, IRuntimeComponentTypes.THICK_CLIENT);
                return new Launcher(info.getComponent(), info.getExecutor());
            } catch (MatchingRuntimeNotFound e) {
                throw new ToolException("не найдена установка 1С:Предприятия для базы " + infobase.getName()
                    + " (версия в свойствах базы: " + infobase.getVersion() + "): " + e.getMessage()
                    + ". Проверьте list_runtime_versions и версию платформы в свойствах базы", e);
            } catch (RuntimeExecutionException e) {
                throw new ToolException("не удалось получить исполнитель конфигуратора для базы "
                    + infobase.getName() + ": " + e.getMessage(), e);
            }
        }
    }
}
