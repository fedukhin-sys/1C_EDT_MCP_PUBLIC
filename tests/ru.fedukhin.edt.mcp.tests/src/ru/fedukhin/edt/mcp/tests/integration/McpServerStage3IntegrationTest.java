package ru.fedukhin.edt.mcp.tests.integration;

import static org.junit.Assert.assertTrue;

import org.junit.Ignore;
import org.junit.Test;

/**
 * Stage 3 e2e placeholder. Will exercise list/get/create/delete/associate infobase
 * + deploy_project over MCP HTTP+SSE on a real demo configuration once the Stage 0
 * Jackson LinkageError under tycho-surefire is fixed. See {@link McpServerIntegrationTest}
 * for the same Ignore rationale.
 */
@Ignore("Jackson LinkageError under tycho-surefire — see McpServerIntegrationTest")
public class McpServerStage3IntegrationTest {

    @Test
    public void infobaseLifecycle_andDeploy_roundTripOverMcp() {
        assertTrue("placeholder; see class javadoc for re-enable plan", true);
    }
}
