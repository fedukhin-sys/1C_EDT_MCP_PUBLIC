package ru.fedukhin.edt.mcp.tools.quality.internal;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import jakarta.inject.Inject;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Synchronous-by-timeout wrapper around {@link ICheckScheduler}. Schedules a validation
 * pass and waits for the derived-data pipeline to finish via
 * {@link IDerivedDataManager#waitAllComputations(long)} (Spike 3). Then reads the resulting
 * markers via {@link MarkerReader}.
 *
 * <p>API note (vs. Plan 2 draft): {@link IDerivedDataManagerProvider} lives in
 * {@code com._1c.g5.v8.dt.core.platform} but {@link IDerivedDataManager} itself is in
 * {@code com._1c.g5.v8.derived}. The provider's accessor is {@code get(IProject)}
 * (not {@code getDerivedDataManager}). {@code waitAllComputations} declares
 * {@code InterruptedException}; on interrupt we restore the flag and treat the run as
 * uncompleted.
 */
public class CheckRunner {

    private final ICheckScheduler             scheduler;
    private final IDerivedDataManagerProvider derivedDataProvider;
    private final MarkerReader                markerReader;

    @Inject
    public CheckRunner(ICheckScheduler scheduler,
                       IDerivedDataManagerProvider derivedDataProvider,
                       MarkerReader markerReader) {
        this.scheduler            = scheduler;
        this.derivedDataProvider  = derivedDataProvider;
        this.markerReader         = markerReader;
    }

    /**
     * @param project     IProject to validate (required)
     * @param scopeObjects collection of scope objects (Long/String/URI per Spike 3); pass
     *                    null/empty list for "whole project"
     * @param path        project-relative путь того же scope'а (или {@code null} для проекта
     *                    целиком) — им фильтруются вычитываемые маркеры: без этого клиент,
     *                    попросивший проверить один файл, получал маркеры всей конфигурации
     * @param checkIds    set of full CheckUid strings (or empty for "all enabled checks")
     * @param waitSeconds maximum wait for completion (clamped externally to [1, 600])
     * @param clearFirst  if true and checkIds is non-empty, runs {@link ICheckScheduler#scheduleClearance}
     *                    before validation
     */
    public CheckRunResult run(IProject project,
                              List<Object> scopeObjects,
                              String path,
                              Set<String> checkIds,
                              int waitSeconds,
                              boolean clearFirst) {
        Set<String> ids = checkIds == null ? Collections.emptySet() : new LinkedHashSet<>(checkIds);
        List<Object> scope = scopeObjects == null ? Collections.emptyList() : scopeObjects;
        if (clearFirst && !ids.isEmpty()) {
            scheduler.scheduleClearance(project, ids, new NullProgressMonitor());
        }
        scheduler.scheduleValidation(project, ids, scope, new NullProgressMonitor());

        IDerivedDataManager dd = derivedDataProvider.get(project);
        long timeoutMs = waitSeconds * 1000L;
        boolean completed;
        try {
            completed = dd.waitAllComputations(timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            completed = false;
        }

        List<CheckMarker> markers = markerReader.read(project, path, null,
                ids.isEmpty() ? null : ids, null);

        return new CheckRunResult(completed, waitSeconds, markers,
                CheckRunResult.SeveritySummary.of(markers),
                Collections.emptySet());
    }
}
