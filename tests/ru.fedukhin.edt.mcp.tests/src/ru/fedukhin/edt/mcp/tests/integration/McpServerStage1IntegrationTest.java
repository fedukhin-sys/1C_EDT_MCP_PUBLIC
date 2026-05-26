package ru.fedukhin.edt.mcp.tests.integration;

import static org.junit.Assert.assertTrue;

import org.junit.Ignore;
import org.junit.Test;

/**
 * Stage 1 e2e placeholder. Will exercise create_project → list_projects →
 * close_project round-trips over MCP HTTP+SSE once
 * the Stage 0 Jackson LinkageError under tycho-surefire is fixed. See
 * {@link McpServerIntegrationTest} for the same Ignore rationale.
 */
@Ignore("Jackson LinkageError under tycho-surefire — see McpServerIntegrationTest")
public class McpServerStage1IntegrationTest {

    @Test
    public void createListDelete_roundTripsForConfigurationProject() {
        assertTrue("placeholder; see class javadoc for re-enable plan", true);
    }
}
