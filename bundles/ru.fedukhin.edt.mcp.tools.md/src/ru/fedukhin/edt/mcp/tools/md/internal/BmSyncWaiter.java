package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * Ожидание того, что мутация BM реально доехала до диска.
 *
 * <p>{@code BmPersistentExecutor.execute} возвращается ДО того, как {@code .mdo} записан:
 * глобальный контекст редактирования сериализует модель асинхронно. Инструмент, который сразу
 * после мутации читает файл (или отдаёт управление клиенту, а тот зовёт следующий инструмент),
 * видит старое содержимое — это и есть класс гонок BUG-03.
 *
 * <p>Логика жила только в {@code create_md_object}; rename/set_md_property возвращались сразу,
 * поэтому немедленное чтение после них отдавало прежнее имя или значение.
 */
public final class BmSyncWaiter {

    /** Потолок ожидания файла — как в create_md_object. */
    public static final long DEFAULT_MAX_MILLIS = 3000;

    private BmSyncWaiter() {}

    /** Дожидается синхронизации модели и подтягивает workspace-кэш к диску. */
    public static void awaitModel(IBmModelManager bmModelManager, IProject project) {
        if (bmModelManager != null) {
            try { bmModelManager.waitModelSynchronization(project); }
            catch (Throwable ignored) { /* best-effort: сервис может быть недоступен в тестах */ }
        }
        refresh(project);
    }

    /**
     * Ждёт появления файла с экспоненциальным backoff (50/100/200/400/800/1600 мс).
     *
     * @return {@code true}, если файл появился до истечения {@code maxMillis}
     */
    public static boolean awaitFile(IProject project, String relPath, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        long sleep = 50L;
        while (true) {
            refresh(project);
            IFile f = project.getFile(relPath);
            if (f != null && f.exists()) return true;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return false;
            if (!sleep(Math.min(sleep, remaining))) return false;
            sleep = Math.min(sleep * 2, 1600L);
        }
    }

    /**
     * Ждёт, пока файл не только появится, но и перестанет расти.
     *
     * <p>Размер проверяется на двух тиках подряд: сериализатор EDT создаёт файл до того, как
     * допишет его, и чтение «свежесозданного» .mdo может застать половину XML.
     *
     * @return {@code true}, если файл появился и его размер стабилен
     */
    public static boolean awaitStableFile(IProject project, String relPath, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        if (!awaitFile(project, relPath, maxMillis)) return false;
        long previousSize = -1;
        while (true) {
            long size = sizeOf(project, relPath);
            if (size >= 0 && size == previousSize) return true;
            previousSize = size;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return size >= 0;
            if (!sleep(Math.min(50L, remaining))) return size >= 0;
            refresh(project);
        }
    }

    private static long sizeOf(IProject project, String relPath) {
        IFile f = project.getFile(relPath);
        if (f == null || f.getLocation() == null) return -1;
        return f.getLocation().toFile().length();
    }

    private static void refresh(IProject project) {
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }
    }

    /** @return {@code false}, если поток прервали — тогда ждать дальше нельзя */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
