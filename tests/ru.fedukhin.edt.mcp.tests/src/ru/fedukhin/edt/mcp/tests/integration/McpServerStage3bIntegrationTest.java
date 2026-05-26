package ru.fedukhin.edt.mcp.tests.integration;

import static org.junit.Assert.assertTrue;

import org.junit.Ignore;
import org.junit.Test;

/**
 * Stage 3b e2e placeholder. Will exercise run_client → list_running_clients → stop_client
 * over MCP HTTP+SSE on a real demo configuration once the Stage 0 Jackson LinkageError
 * under tycho-surefire is fixed. See {@link McpServerIntegrationTest} for the same Ignore rationale.
 */
@Ignore("Jackson LinkageError under tycho-surefire — see McpServerIntegrationTest")
public class McpServerStage3bIntegrationTest {

    @Test
    public void runListStop_roundTripOverMcp() {
        assertTrue("placeholder; see class javadoc for re-enable plan", true);
    }
}
