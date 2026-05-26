package ru.fedukhin.edt.mcp.tests.integration;

import org.junit.Ignore;
import org.junit.Test;

/**
 * Integration placeholder for the Stage 3c debug tools. {@code @Ignore}'d for the same
 * reason every integration test in this repo is — the headless tycho-surefire target
 * cannot start the {@code xtext.ui → ui.workbench} chain (see Stage 2 spec §14 and the
 * Phase 0 spike notes). Stage 3c's end-to-end path is verified by the manual IDE smoke
 * test (README "Manual sanity check" / spec §10.5), the established pattern for Stages
 * 1 / 3 / 3b.
 */
@Ignore("Headless xtext.ui/ui.workbench wall — see 2026-05-14-spike-stage-3c-debug.md; verified by manual IDE smoke")
public class McpServerStage3cIntegrationTest {

    @Test
    public void debugToolsRoundTrip_placeholder() {
        // Manual smoke only — see README "Manual sanity check (Stage 3c)".
    }

    @Test
    public void debugClientLaunchRoundTrip_placeholder() {
        // Manual smoke only: debug_client -> set_breakpoint -> (hit) -> get_stack ->
        // get_variables -> evaluate -> debug_resume -> stop_debug. See spec §10.5.
    }
}
