package ru.fedukhin.edt.mcp.tests.tools.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.Optional;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.client.internal.InfobaseLookup;

public class InfobaseLookupTest {

    @Test
    public void findByName_returnsRefWhenPresent() {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(mgr.findInfobaseByName("Demo")).thenReturn(Optional.of(ref));

        InfobaseLookup lookup = new InfobaseLookup(mgr);
        Optional<InfobaseReference> result = lookup.findByName("Demo");

        assertTrue(result.isPresent());
        assertEquals(ref, result.get());
    }

    @Test
    public void findByName_returnsEmptyWhenAbsent() {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        when(mgr.findInfobaseByName("Missing")).thenReturn(Optional.empty());

        InfobaseLookup lookup = new InfobaseLookup(mgr);
        assertFalse(lookup.findByName("Missing").isPresent());
    }
}
