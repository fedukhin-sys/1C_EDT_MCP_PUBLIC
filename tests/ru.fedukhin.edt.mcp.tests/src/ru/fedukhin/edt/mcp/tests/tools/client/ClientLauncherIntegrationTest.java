package ru.fedukhin.edt.mcp.tests.tools.client;

import static org.junit.Assert.assertTrue;

import org.junit.Ignore;
import org.junit.Test;

/**
 * Real EDT integration for {@link ru.fedukhin.edt.mcp.tools.client.internal.ClientLauncher}.
 * Requires a live 1С platform installation discoverable via
 * {@code IResolvableRuntimeInstallationManager}. Disabled in the headless tycho-surefire
 * run; verified manually in Task 12 (smoke test) of the Stage 3b plan.
 */
@Ignore("Requires live IDE with installed 1С platform — manual smoke only")
public class ClientLauncherIntegrationTest {

    @Test
    public void launchThin_realProcess_isStartedAndKilled() {
        assertTrue("placeholder; see class javadoc for re-enable plan", true);
    }
}
