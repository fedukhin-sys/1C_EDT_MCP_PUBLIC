package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.debug.GetVariablesTool;
import ru.fedukhin.edt.mcp.tools.eventlog.QueryEventLogTool;

public class CapabilityTest {
    @Test public void sensitiveToolsDeclareCapability() {
        assertTrue(new GetVariablesTool(null, null).returnsInfobaseData());
        assertTrue(new QueryEventLogTool(null).returnsInfobaseData());
    }
}
