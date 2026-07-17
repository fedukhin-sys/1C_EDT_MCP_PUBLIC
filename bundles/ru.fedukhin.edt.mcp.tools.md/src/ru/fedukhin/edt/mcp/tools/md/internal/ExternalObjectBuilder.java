package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumper;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Сборка внешнего объекта в {@code .epf}/{@code .erf} штатным сервисом EDT
 * {@link IExternalObjectDumper} — тем же, что стоит за «Экспортом» в IDE.
 *
 * <p>Сервис сам выгружает объект в XML конфигуратора, находит ИБ (через приложение,
 * ассоциированное с проектом), подставляет учётку и запускает платформу. Поэтому здесь
 * нет ни строки подключения к ИБ, ни резолва {@code 1cv8.exe}, ни разбора его лога:
 * до v1.18.1 всё это делалось руками и требовало свободной служебной ИБ в аргументах.
 *
 * <p>Остаётся то, чего сервис не делает: удалить прошлый файл (иначе «dump вхолостую»
 * выглядит успехом), ограничить время и убедиться, что файл на диске непуст. Проверку
 * синтаксиса BSL не делает никто — за это отвечает {@link BuildPrecheck} до вызова.
 *
 * <p>Сеймы ({@link ObjectResolver}, сам {@link IExternalObjectDumper}) позволяют тестам
 * прогонять оркестрацию без EDT и без запуска платформы.
 */
public class ExternalObjectBuilder {

    /** Резолв EObject внешнего объекта по FQN. */
    public interface ObjectResolver {
        EObject resolve(IProject project, String fqn) throws ToolException;
    }

    /**
     * Успех быстрее этого порога — повод предупредить: платформа умеет вернуться,
     * не сделав ничего (класс BUG-18), а файл мог остаться от прошлой сборки.
     */
    private static final long SUSPICIOUS_BUILD_MS = 3_000L;

    /** @param outFile куда положить .epf/.erf */
    public record BuildRequest(IProject project, String fqn, Path outFile, int timeoutSeconds) { }

    public record BuildOutcome(Path epfPath, long durationMs, List<String> warnings) { }

    private final IExternalObjectDumper dumper;
    private final ObjectResolver        resolver;

    @Inject
    public ExternalObjectBuilder(IExternalObjectDumper dumper, IBmModelManager bmModelManager) {
        this(dumper, new BmObjectResolver(bmModelManager));
    }

    /** Test seam. */
    public ExternalObjectBuilder(IExternalObjectDumper dumper, ObjectResolver resolver) {
        this.dumper   = dumper;
        this.resolver = resolver;
    }

    public BuildOutcome build(BuildRequest req) throws ToolException {
        EObject object = resolver.resolve(req.project(), req.fqn());

        // Файл мог остаться от прошлой сборки: без удаления «dump вхолостую» выглядит как успех.
        try { Files.deleteIfExists(req.outFile()); }
        catch (IOException e) { throw new ToolException("не удалось перезаписать " + req.outFile()
                + ": " + e.getMessage(), e); }

        long t0 = System.currentTimeMillis();
        dump(req, object);
        long durationMs = System.currentTimeMillis() - t0;

        // Возврат без исключения ничего не гарантирует — верим только файлу на диске.
        if (!Files.isRegularFile(req.outFile())) {
            throw new ToolException("EDT завершил выгрузку без ошибки, но файл " + req.outFile()
                + " не создан");
        }
        long size;
        try { size = Files.size(req.outFile()); }
        catch (IOException e) { throw new ToolException("не удалось прочитать " + req.outFile()
                + ": " + e.getMessage(), e); }
        if (size == 0) {
            throw new ToolException("EDT завершил выгрузку без ошибки, но файл " + req.outFile()
                + " пуст");
        }

        List<String> warnings = new ArrayList<>();
        if (durationMs < SUSPICIOUS_BUILD_MS) {
            warnings.add("выгрузка отработала за " + durationMs
                + " мс — подозрительно быстро для реальной сборки (класс BUG-18): "
                + "проверьте, что .epf действительно пересобран");
        }
        return new BuildOutcome(req.outFile(), durationMs, warnings);
    }

    /**
     * Прогон {@link IExternalObjectDumper#dump} с ограничением по времени.
     *
     * <p>Сервис синхронный и на занятой ИБ умеет ждать сколько угодно, поэтому крутим его в
     * отдельном потоке: по таймауту отменяем {@link IProgressMonitor} — единственный способ
     * попросить EDT остановиться (платформу он гасит сам).
     */
    private void dump(BuildRequest req, EObject object) throws ToolException {
        IProgressMonitor monitor = new NullProgressMonitor();
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "edt-mcp-epf-dump");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<?> task = pool.submit(() -> {
                dumper.dump(req.project(), object, req.outFile(), monitor);
                return null;
            });
            try {
                task.get(req.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                monitor.setCanceled(true);
                throw new ToolException("выгрузка .epf: timeout after " + req.timeoutSeconds()
                    + "s; ИБ проекта могла быть занята другим сеансом", e);
            } catch (InterruptedException e) {
                monitor.setCanceled(true);
                Thread.currentThread().interrupt();
                throw new ToolException("сборка .epf прервана", e);
            } catch (ExecutionException e) {
                throw toToolException(e.getCause());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * CoreException от EDT — единственная диагностика провала, а её типовая причина
     * (у проекта нет ИБ, в которую платформа загрузила бы объект) без подсказки необъяснима.
     */
    private static ToolException toToolException(Throwable cause) {
        if (cause instanceof CoreException e) {
            return new ToolException("EDT не смог собрать внешний объект: " + describe(e.getStatus())
                + "; если причина в отсутствии информационной базы у проекта — свяжите проект с ИБ "
                + "(associate_infobase) или создайте её (create_infobase)", e);
        }
        if (cause instanceof IllegalArgumentException e) {
            // Сервис требует проект вида external-object; иначе — падает именно так.
            return new ToolException("проект не является проектом внешних отчётов и обработок: "
                + e.getMessage(), e);
        }
        if (cause instanceof RuntimeException e) throw e;
        return new ToolException("сборка .epf не удалась: " + cause, cause);
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

    /**
     * Production-резолвер: top-object по FQN в BM-модели проекта.
     *
     * <p>Объект достаётся read-only задачей и используется снаружи неё: так же поступает
     * сам EDT ({@code ExternalObjectDumpSupport}), потому что держать BM-транзакцию всё
     * время сборки (минуты, запуск платформы) нельзя.
     */
    static final class BmObjectResolver implements ObjectResolver {
        private final IBmModelManager bmModelManager;
        BmObjectResolver(IBmModelManager bmModelManager) { this.bmModelManager = bmModelManager; }

        @Override public EObject resolve(IProject project, String fqn) throws ToolException {
            bmModelManager.waitModelSynchronization(project);
            IBmModel model = bmModelManager.getModel(project);
            if (model == null) {
                throw new ToolException("проект '" + project.getName()
                    + "' не проиндексирован EDT (нет BM-модели)");
            }
            EObject object = model.executeReadonlyTask(new IBmTask<EObject>() {
                @Override public String getName() { return "resolve external object " + fqn; }
                @Override public Object getId() { return null; }
                @Override public Object getServiceId() { return null; }
                @Override public EObject execute(com._1c.g5.v8.bm.core.IBmTransaction txn,
                                                 IProgressMonitor monitor) {
                    return txn.getTopObjectByFqn(fqn);
                }
            });
            if (object == null) {
                throw new ToolException("внешний объект '" + fqn + "' не найден в BM-модели проекта '"
                    + project.getName() + "'");
            }
            return object;
        }
    }
}
