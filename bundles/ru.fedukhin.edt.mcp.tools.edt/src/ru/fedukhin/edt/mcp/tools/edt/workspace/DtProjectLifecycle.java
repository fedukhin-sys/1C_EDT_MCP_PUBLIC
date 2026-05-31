package ru.fedukhin.edt.mcp.tools.edt.workspace;

import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import org.eclipse.core.resources.IProject;

/**
 * Observes EDT's asynchronous DT-project lifecycle so {@code close_project} /
 * {@code open_project} return a stable, usable result instead of returning while
 * EDT is still settling.
 *
 * <p>EDT registers and unregisters the DT project of an {@link IProject} on
 * background jobs. After a {@code deploy_project} changes the infobase, EDT may
 * require an interactive data migration; a programmatic close/open then opens the
 * project but EDT stops its context again a few seconds later — the project
 * degrades to {@code type:unknown} with an inactive BM namespace (BUG-NEW-B).
 * That migration is UI-coupled and cannot be driven from the MCP server, but
 * {@code open_project} can wait, detect the unstable state and surface a clear
 * warning instead of leaving the caller with a silently broken project.
 */
public final class DtProjectLifecycle {

    private final IV8ProjectManager projectManager;
    private final long pollMs;
    private final long registerTimeoutMs;
    private final long stableWindowMs;
    private final long drainTimeoutMs;

    /** Production timings: poll 0.5s, register wait 75s, stability window 12s, drain wait 30s. */
    public static DtProjectLifecycle production(IV8ProjectManager projectManager) {
        return new DtProjectLifecycle(projectManager, 500L, 75_000L, 12_000L, 30_000L);
    }

    public DtProjectLifecycle(IV8ProjectManager projectManager, long pollMs,
                              long registerTimeoutMs, long stableWindowMs, long drainTimeoutMs) {
        this.projectManager = projectManager;
        this.pollMs = pollMs;
        this.registerTimeoutMs = registerTimeoutMs;
        this.stableWindowMs = stableWindowMs;
        this.drainTimeoutMs = drainTimeoutMs;
    }

    private boolean registered(IProject project) {
        return projectManager.getProject(project) != null;
    }

    /**
     * After {@code IProject.close()}, waits (best-effort) until EDT has
     * unregistered the DT project, so a later open does not race a still-pending
     * teardown job.
     */
    public void drainAfterClose(IProject project) throws InterruptedException {
        long deadline = System.currentTimeMillis() + drainTimeoutMs;
        while (registered(project) && System.currentTimeMillis() < deadline) {
            Thread.sleep(pollMs);
        }
    }

    /**
     * After {@code IProject.open()}, waits until the DT project is registered and
     * stays registered for a stability window.
     *
     * @return {@code null} when the project activated and stayed active, or a
     *         warning describing the likely pending EDT data migration (BUG-NEW-B)
     */
    public String awaitActivation(IProject project) throws InterruptedException {
        if (awaitRegistered(project) && staysRegistered(project)) {
            return null;
        }
        return "project '" + project.getName() + "' was opened but its DT model did "
             + "not activate. EDT most likely requires an interactive data migration "
             + "(typical after a deploy_project changed the infobase) — open the "
             + "project in the EDT IDE and complete the migration dialog. Until then "
             + "metadata / BSL / form tools on this project fail with 'namespace "
             + "inactive'.";
    }

    /** Waits until the DT project becomes registered (after an open). */
    private boolean awaitRegistered(IProject project) throws InterruptedException {
        long deadline = System.currentTimeMillis() + registerTimeoutMs;
        while (!registered(project)) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(pollMs);
        }
        return true;
    }

    /** Returns {@code true} if the DT project stays registered for the whole stability window. */
    private boolean staysRegistered(IProject project) throws InterruptedException {
        long deadline = System.currentTimeMillis() + stableWindowMs;
        while (System.currentTimeMillis() < deadline) {
            if (!registered(project)) {
                return false;
            }
            Thread.sleep(pollMs);
        }
        return registered(project);
    }
}
