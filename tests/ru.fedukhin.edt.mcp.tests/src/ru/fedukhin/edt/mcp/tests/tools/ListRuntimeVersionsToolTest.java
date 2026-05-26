package ru.fedukhin.edt.mcp.tests.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.version.Version;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.edt.workspace.ListRuntimeVersionsTool;

public class ListRuntimeVersionsToolTest {

    @Test
    public void call_returnsAllVersions_maxIsDefault() throws Exception {
        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);
        IRuntime r1 = mock(IRuntime.class); when(r1.getVersion()).thenReturn(Version.V8_3_22);
        IRuntime r2 = mock(IRuntime.class); when(r2.getVersion()).thenReturn(Version.V8_3_24);
        when(rr.getRuntimes()).thenReturn(Arrays.asList(r1, r2));

        ListRuntimeVersionsTool tool = new ListRuntimeVersionsTool(rr);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) tool.call(Collections.emptyMap());

        assertEquals(2, result.size());
        // Order: insertion order (whatever getRuntimes() returns).
        Map<String, Object> first = result.get(0);
        Map<String, Object> second = result.get(1);
        assertEquals(String.valueOf(Version.V8_3_22), first.get("version"));
        assertEquals(Boolean.FALSE, first.get("isDefault"));
        assertEquals(String.valueOf(Version.V8_3_24), second.get("version"));
        assertEquals(Boolean.TRUE, second.get("isDefault"));
    }

    @Test
    public void call_emptyRegistryReturnsEmptyList() throws Exception {
        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);
        when(rr.getRuntimes()).thenReturn(Collections.emptyList());
        ListRuntimeVersionsTool tool = new ListRuntimeVersionsTool(rr);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) tool.call(Collections.emptyMap());
        assertTrue(result.isEmpty());
    }

    @Test
    public void metadata_isCorrect() {
        ListRuntimeVersionsTool tool = new ListRuntimeVersionsTool(mock(IRuntimeRegistry.class));
        assertEquals("list_runtime_versions", tool.name());
        assertFalse(tool.description().isEmpty());
    }
}
