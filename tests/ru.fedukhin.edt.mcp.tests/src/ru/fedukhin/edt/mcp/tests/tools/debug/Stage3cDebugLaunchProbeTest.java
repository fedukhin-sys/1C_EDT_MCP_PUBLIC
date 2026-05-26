package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertNotNull;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.junit.Ignore;
import org.junit.Test;

/**
 * PHASE 0 SPIKE PROBE — Stage 3c, plan Task 1 Step 5 (risk #1, HIGH).
 *
 * <p>This is a THROWAWAY probe, not a real test. It answers exactly one question:
 * does the {@code org.eclipse.debug.core} launch machinery + EDT's debug
 * launch-config-type lookup chain LOAD AND RUN in the headless tycho-surefire
 * runtime, or does it hit the same {@code xtext.ui -> ui.workbench -> SWT}
 * activation chain that forces every integration test in this repo to
 * {@code @Ignore}?
 *
 * <p>It does NOT attempt a full successful debug launch (the headless test
 * workspace has no registered infobase/project, and 8.3.x platform resolution
 * needs a live install). Instead each step is wrapped in a watchdog: if the
 * call returns (even with a domain exception) we LEARNED the machinery is
 * reachable headless; if it HANGS past the watchdog we LEARNED it hits the SWT
 * wall. Either way the probe records the outcome and never blocks the build.
 *
 * <p>Kept {@code @Ignore}'d per plan Step 5 ("delete the probe test afterwards,
 * or keep it @Ignore'd as a Plan-2 seed") so the 177-test baseline is unchanged.
 * Run manually with:
 * {@code mvn clean verify "-Dtest=Stage3cDebugLaunchProbeTest" -DfailIfNoTests=false}
 * after temporarily removing the {@code @Ignore}. See
 * docs/superpowers/notes/2026-05-14-spike-stage-3c-debug.md for findings.
 */
@Ignore("Phase 0 spike probe — manual run only; see spike-notes 2026-05-14-spike-stage-3c-debug.md")
public class Stage3cDebugLaunchProbeTest {

    /** Public launch-config type — declared in com._1c.g5.v8.dt.debug.core/plugin.xml. */
    private static final String REMOTE_RUNTIME_TYPE_ID =
            "com._1c.g5.v8.dt.debug.core.RemoteRuntime";
    /** Internal (public="false") launch-config type used for local debug-server launches. */
    private static final String LOCAL_RUNTIME_TYPE_ID =
            "com._1c.g5.v8.dt.debug.core.LocalRuntime";

    private static final long WATCHDOG_SECONDS = 60L;

    @Test
    public void probe_headlessDebugLaunchMachinery() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            // ---- Probe A: can we even get the ILaunchManager headless? ----
            ILaunchManager lm = runWithWatchdog(exec, "DebugPlugin.getLaunchManager",
                    () -> DebugPlugin.getDefault().getLaunchManager());
            assertNotNull("ILaunchManager unavailable headless", lm);
            System.out.println("[SPIKE] ILaunchManager OK: " + lm);

            // ---- Probe B: launch-config-type lookup (risk #3 confirmation) ----
            ILaunchConfigurationType remoteType = runWithWatchdog(exec,
                    "getLaunchConfigurationType(RemoteRuntime)",
                    () -> lm.getLaunchConfigurationType(REMOTE_RUNTIME_TYPE_ID));
            System.out.println("[SPIKE] RemoteRuntime type lookup -> " + remoteType
                    + " (modes=" + (remoteType == null ? "n/a" : remoteType.getSupportedModes()) + ")");

            ILaunchConfigurationType localType = runWithWatchdog(exec,
                    "getLaunchConfigurationType(LocalRuntime)",
                    () -> lm.getLaunchConfigurationType(LOCAL_RUNTIME_TYPE_ID));
            System.out.println("[SPIKE] LocalRuntime type lookup -> " + localType);

            // ---- Probe C: build a working copy (no UI needed) ----
            ILaunchConfigurationType typeForWc = remoteType != null ? remoteType : localType;
            assertNotNull("Neither EDT debug launch-config type resolved", typeForWc);
            ILaunchConfigurationWorkingCopy wc = runWithWatchdog(exec,
                    "newInstance(workingCopy)",
                    () -> typeForWc.newInstance(null, "spike-3c-probe"));
            System.out.println("[SPIKE] working copy created: " + wc);

            // ---- Probe D: the HIGH-risk call — launch("debug", monitor). ----
            // We EXPECT a domain CoreException/IllegalStateException here because
            // the config has no infobase/runtime attributes set. What we are
            // probing is: does the call RETURN (machinery reachable headless) or
            // HANG (SWT wall)?
            try {
                ILaunch launch = runWithWatchdog(exec, "workingCopy.launch(\"debug\")",
                        () -> wc.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor()));
                System.out.println("[SPIKE] launch(\"debug\") RETURNED: " + launch);
                if (launch != null) {
                    IDebugTarget[] targets = launch.getDebugTargets();
                    System.out.println("[SPIKE] debug targets: " + targets.length);
                }
            } catch (ExecutionException ee) {
                // Domain exception = GOOD NEWS: machinery ran headless, just
                // rejected our incomplete config.
                System.out.println("[SPIKE] launch(\"debug\") threw (machinery reachable, "
                        + "config incomplete as expected): " + ee.getCause());
                Throwable c = ee.getCause();
                while (c != null) {
                    System.out.println("[SPIKE]   cause: " + c);
                    StackTraceElement[] st = c.getStackTrace();
                    for (int i = 0; i < Math.min(st.length, 12); i++) {
                        System.out.println("[SPIKE]     at " + st[i]);
                    }
                    c = c.getCause();
                }
            }
        } catch (TimeoutException te) {
            // The watchdog tripped — the call hung. THIS is the SWT-wall finding.
            System.out.println("[SPIKE] *** WATCHDOG TRIPPED — call hung headless: "
                    + te.getMessage() + " ***");
            throw te;
        } finally {
            // Clean shutdown: do NOT shutdownNow() — interrupting a worker mid
            // OSGi class-load corrupts the framework and trips a non-zero exit
            // during teardown. All probe steps have already returned by here.
            exec.shutdown();
            exec.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static <T> T runWithWatchdog(ExecutorService exec, String label, Callable<T> call)
            throws Exception {
        Future<T> f = exec.submit(call);
        try {
            return f.get(WATCHDOG_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            throw new TimeoutException("step '" + label + "' hung > " + WATCHDOG_SECONDS + "s");
        }
    }
}
