package ru.fedukhin.edt.mcp.tests.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.internal.preferences.McpPreferences;

public class McpPreferencesTest {

    private static final String NODE = "ru.fedukhin.edt.mcp.core";

    @After
    public void clear() throws Exception {
        IEclipsePreferences n = InstanceScope.INSTANCE.getNode(NODE);
        n.clear();
        n.flush();
    }

    @Test
    public void defaults_areApplied() {
        McpPreferences p = new McpPreferences(InstanceScope.INSTANCE.getNode(NODE));
        assertEquals(3001, p.getPort());
        assertFalse(p.isAutoStart());
        assertEquals("127.0.0.1", p.getBindHost());
    }

    @Test
    public void setPort_persists() throws Exception {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(NODE);
        McpPreferences p = new McpPreferences(node);
        p.setPort(31234);
        node.flush();
        assertEquals(31234, new McpPreferences(InstanceScope.INSTANCE.getNode(NODE)).getPort());
    }
}
