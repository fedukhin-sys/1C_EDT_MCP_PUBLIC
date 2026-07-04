package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.*;   // Sensitivity, PiiCatalog, CatalogStore, InfobaseFlagStore, AuditLog, PrivacyState
import ru.fedukhin.edt.mcp.tools.privacy.*;

public class PrivacyToolsTest {
    @Test public void setFlagThenAuditVisible() throws Exception {
        InfobaseFlagStore flags = new InfobaseFlagStore(new HashMap<>());
        AuditLog audit = new AuditLog();
        Object r = new SetInfobasePiiFlagTool(flags, audit)
            .call(Map.of("infobase","TestBase","containsRealPersonalData", false));
        assertEquals(Boolean.FALSE, ((Map<?,?>) r).get("containsRealPersonalData"));
        assertFalse(flags.containsRealPersonalData("TestBase"));

        Object a = new GetPrivacyAuditTool(audit).call(Map.of("limit", 10));
        assertFalse(((List<?>)((Map<?,?>) a).get("entries")).isEmpty());
    }

    @Test public void getPiiCatalogReturnsCurrentUnion() throws Exception {
        CatalogStore store = new CatalogStore(List::of); // no on-disk catalogs → empty union
        Object out = new GetPiiCatalogTool(store).call(Map.of());
        assertTrue(((Map<?,?>) out).containsKey("objects"));
        assertTrue(((Map<?,?>) out).containsKey("attributes"));
        assertTrue(((Map<?,?>) out).get("objects") instanceof Map);
        assertTrue(((Map<?,?>) out).get("attributes") instanceof Map);
    }
}
