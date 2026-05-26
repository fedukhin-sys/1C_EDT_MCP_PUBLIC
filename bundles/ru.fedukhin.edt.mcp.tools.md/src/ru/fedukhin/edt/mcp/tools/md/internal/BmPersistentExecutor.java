package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmPlatformGlobalEditingContext;
import com._1c.g5.v8.bm.integration.IBmPlatformTask;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.core.resources.IProject;

/**
 * Выполняет single-namespace BM-задачу в <b>глобальном</b> контексте редактирования
 * с авто-сохранением изменений в ресурсы workspace (.mdo на диске).
 *
 * <p>{@link IBmModelManager#executeReadWriteTask} — это «редактирование вне
 * контекста»: изменения попадают в BM-индекс, но **не** в .mdo на диске.
 * Из-за этого после {@code create_md_object} / {@code set_md_property} /
 * {@code rename_*} workspace остаётся out-of-sync, F5 в IDE их не подхватывает,
 * а после рестарта BM откатывается к версии .mdo (как Stage 7b Task #9 показал).
 *
 * <p>Глобальный контекст ({@link IBmPlatformGlobalEditingContext}) — это
 * рекомендуемый EDT путь для рефакторингов и операций панелей: после успешного
 * {@code execute(...)} изменения автоматически сериализуются в .mdo
 * (см. EDT docs «Глобальный контекст редактирования»). Существующие
 * single-namespace task'и переиспользуются через
 * {@link IBmPlatformTransaction#getNamespaceBoundTransaction(IBmNamespace)} —
 * это namespace-scoped view над multi-namespace транзакцией.
 *
 * <p>Использовать вместо {@code bmModelManager.executeReadWriteTask(project, …)}
 * в любой мутирующей MdObject-операции.
 */
@Singleton
public class BmPersistentExecutor {

    private final IBmModelManager bmModelManager;

    @Inject
    public BmPersistentExecutor(IBmModelManager bmModelManager) {
        this.bmModelManager = bmModelManager;
    }

    /**
     * Выполняет задачу в глобальном контексте.
     *
     * @param project — проект, чей namespace используется
     * @param description — человекочитаемое имя задачи (попадает в Undo/Redo и BM events)
     * @param task — single-namespace задача; получит namespace-scoped {@link IBmTransaction}
     * @return результат задачи
     */
    public <T> T execute(IProject project, String description,
                         IBmSingleNamespaceTask<T> task) {
        IBmNamespace ns = bmModelManager.getBmNamespace(project);
        IBmPlatformGlobalEditingContext ctx = bmModelManager.getGlobalEditingContext();
        return ctx.execute(description, null, null,
                (IBmPlatformTask<T>) (IBmPlatformTransaction ptxn) -> {
                    IBmTransaction nsTxn = ptxn.getNamespaceBoundTransaction(ns);
                    return task.execute(nsTxn);
                });
    }
}
