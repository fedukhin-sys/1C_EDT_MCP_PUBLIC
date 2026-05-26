package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.InfobaseType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.infobase.ListInfobasesTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

public class ListInfobasesToolTest {

    @Test
    public void name_isListInfobases() {
        ListInfobasesTool tool = new ListInfobasesTool(mock(InfobaseRegistry.class));
        assertEquals("list_infobases", tool.name());
    }

    @Test
    public void call_emptyRegistry_returnsEmptyList() throws Exception {
        InfobaseRegistry r = mock(InfobaseRegistry.class);
        when(r.listAll()).thenReturn(Collections.emptyList());

        Object result = new ListInfobasesTool(r).call(new HashMap<>());
        assertTrue(result instanceof List);
        assertTrue(((List<?>) result).isEmpty());
    }

    @Test
    public void call_singleFileInfobase_serialisesAllFields() throws Exception {
        UUID id = UUID.randomUUID();
        FileConnectionString cs = mock(FileConnectionString.class);
        when(cs.asConnectionString()).thenReturn("File=\"C:/IB/Demo\";");
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        when(ref.getUuid()).thenReturn(id);
        when(ref.getInfobaseType()).thenReturn(InfobaseType.FILE);
        when(ref.getConnectionString()).thenReturn(cs);
        when(ref.getFolder()).thenReturn("Default");
        when(ref.isShowInList()).thenReturn(true);

        InfobaseRegistry r = mock(InfobaseRegistry.class);
        when(r.listAll()).thenReturn(Collections.singletonList(ref));

        Object result = new ListInfobasesTool(r).call(new HashMap<>());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result;
        assertEquals(1, items.size());
        Map<String, Object> entry = items.get(0);
        assertEquals("Demo", entry.get("name"));
        assertEquals(id.toString(), entry.get("uuid"));
        assertEquals("FILE", entry.get("type"));
        assertEquals("File=\"C:/IB/Demo\";", entry.get("connection"));
        assertEquals("Default", entry.get("folder"));
        assertTrue((Boolean) entry.get("isShown"));
    }

    @Test
    public void call_filterByType_excludesNonMatching() throws Exception {
        InfobaseReference fileRef = mock(InfobaseReference.class);
        when(fileRef.getName()).thenReturn("F");
        when(fileRef.getUuid()).thenReturn(UUID.randomUUID());
        when(fileRef.getInfobaseType()).thenReturn(InfobaseType.FILE);
        FileConnectionString fcs = mock(FileConnectionString.class);
        when(fcs.asConnectionString()).thenReturn("File=\"X\";");
        when(fileRef.getConnectionString()).thenReturn(fcs);

        InfobaseReference srvRef = mock(InfobaseReference.class);
        when(srvRef.getName()).thenReturn("S");
        when(srvRef.getUuid()).thenReturn(UUID.randomUUID());
        when(srvRef.getInfobaseType()).thenReturn(InfobaseType.SERVER);

        InfobaseRegistry r = mock(InfobaseRegistry.class);
        when(r.listAll()).thenReturn(Arrays.asList(fileRef, srvRef));

        Map<String, Object> args = new HashMap<>();
        args.put("type", "FILE");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) new ListInfobasesTool(r).call(args);
        assertEquals(1, items.size());
        assertEquals("F", items.get(0).get("name"));
    }

    @Test
    public void call_filterByFolder_excludesNonMatching() throws Exception {
        InfobaseReference a = mock(InfobaseReference.class);
        when(a.getName()).thenReturn("A");
        when(a.getUuid()).thenReturn(UUID.randomUUID());
        when(a.getInfobaseType()).thenReturn(InfobaseType.FILE);
        FileConnectionString cs = mock(FileConnectionString.class);
        when(cs.asConnectionString()).thenReturn("");
        when(a.getConnectionString()).thenReturn(cs);
        when(a.getFolder()).thenReturn("Demo");

        InfobaseReference b = mock(InfobaseReference.class);
        when(b.getName()).thenReturn("B");
        when(b.getUuid()).thenReturn(UUID.randomUUID());
        when(b.getInfobaseType()).thenReturn(InfobaseType.FILE);
        when(b.getConnectionString()).thenReturn(cs);
        when(b.getFolder()).thenReturn("Other");

        InfobaseRegistry r = mock(InfobaseRegistry.class);
        when(r.listAll()).thenReturn(Arrays.asList(a, b));

        Map<String, Object> args = new HashMap<>();
        args.put("folder", "Demo");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) new ListInfobasesTool(r).call(args);
        assertEquals(1, items.size());
        assertEquals("A", items.get(0).get("name"));
    }
}
