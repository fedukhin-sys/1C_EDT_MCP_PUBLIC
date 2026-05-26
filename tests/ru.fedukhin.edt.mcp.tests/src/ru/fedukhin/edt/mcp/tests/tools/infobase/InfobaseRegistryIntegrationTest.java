package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertTrue;

import org.junit.Ignore;
import org.junit.Test;

/**
 * Real EDT integration for {@link ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry}.
 * Requires a live 1cv8.exe (resolved via {@link ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli})
 * — currently the production resolver throws (SPIKE-PENDING). When the spike lands,
 * this test creates a temp FILE infobase, lists it, deletes it.
 */
@Ignore("RuntimeCli executable resolver pending — see Stage 3 plan Task 3 SPIKE")
public class InfobaseRegistryIntegrationTest {

    @Test
    public void createListDelete_roundTripsAgainstRealCli() {
        assertTrue("placeholder; see class javadoc for re-enable plan", true);
    }
}
