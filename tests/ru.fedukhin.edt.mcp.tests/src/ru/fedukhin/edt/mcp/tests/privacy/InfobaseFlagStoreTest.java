package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.InfobaseFlagStore;

public class InfobaseFlagStoreTest {
    @Test public void defaultsToFailClosedTrue() {
        InfobaseFlagStore s = new InfobaseFlagStore(new java.util.HashMap<>());
        assertTrue(s.containsRealPersonalData("Demo"));   // неизвестная база → есть ПДн
    }
    @Test public void canDisableForTestBase() {
        InfobaseFlagStore s = new InfobaseFlagStore(new java.util.HashMap<>());
        s.setFlag("TestBase", false);
        assertFalse(s.containsRealPersonalData("TestBase"));
        assertTrue(s.containsRealPersonalData("Prod"));
    }
}
