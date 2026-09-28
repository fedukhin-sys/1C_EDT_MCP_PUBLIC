package ru.fedukhin.edt.mcp.core.jobs;

import org.eclipse.core.runtime.IProgressMonitor;

/** То, что тело задания получает от реестра: монитор, шаги и результат. */
public interface JobContext {

    /** Монитор задания. Его отменяют Progress view EDT и лимит времени задания. */
    IProgressMonitor monitor();

    /**
     * Выполняет шаг и записывает его итог с длительностью. Исключение шага записывается как
     * {@link StepStatus#FAILED} и пробрасывается дальше: продолжать ли — решает тело задания.
     *
     * <p>Если монитор задания уже отменён (лимит времени, Progress view EDT), {@code action} не
     * запускается вовсе: шаг записывается как {@link StepStatus#SKIPPED}, и бросается
     * {@code org.eclipse.core.runtime.OperationCanceledException}. Тело задания может поймать её
     * и вернуться штатно — реестр всё равно доведёт задание до CANCELLED/FAILED по причине отмены,
     * а не до SUCCEEDED.
     */
    <T> T step(String name, StepAction<T> action) throws Exception;

    /** Записывает шаг без тела — пропуск или предупреждение. */
    void record(String name, StepStatus status, String message);

    /** Кладёт значение в результат задания. Значение должно сериализоваться в JSON. */
    void put(String key, Object value);
}
