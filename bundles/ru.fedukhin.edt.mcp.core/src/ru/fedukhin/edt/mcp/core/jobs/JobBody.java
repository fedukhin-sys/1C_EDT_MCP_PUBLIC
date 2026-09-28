package ru.fedukhin.edt.mcp.core.jobs;

/**
 * Тело фонового задания. Исключение из тела завершает задание со статусом FAILED.
 *
 * <p>После отмены (лимит времени, Progress view EDT) {@link JobContext#step} перестаёт запускать
 * действия шагов — тело может поймать брошенную им {@code OperationCanceledException} и вернуться
 * раньше срока, не вызывая очередной шаг. Реестр в этом случае доводит задание до CANCELLED (или
 * FAILED с причиной таймаута) сам, даже если тело вышло без исключения.
 */
@FunctionalInterface
public interface JobBody {
    void run(JobContext ctx) throws Exception;
}
