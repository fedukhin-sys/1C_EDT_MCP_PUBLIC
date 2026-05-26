package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.InfobaseType;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.GetInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

public class GetInfobaseToolTest {

    @Test
    public void call_byName_returnsEntry() throws Exception {
        UUID id = UUID.randomUUID();
        FileConnectionString cs = mock(FileConnectionString.class);
        when(cs.asConnectionString()).thenReturn("File=\"X\";");
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        when(ref.getUuid()).thenReturn(id);
        when(ref.getInfobaseType()).thenReturn(InfobaseType.FILE);
        when(ref.getConnectionString()).thenReturn(cs);

        InfobaseRegistry r = mock(InfobaseRegistry.class);
        when(r.findByName("Demo")).thenReturn(Optional.of(ref));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Demo");
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) new GetInfobaseTool(r).call(args);
        assertEquals("Demo", entry.get("name"));
        assertEquals(id.toString(), entry.get("uuid"));
        assertEquals("FILE", entry.get("type"));
    }

    @Test
    public void call_byUuid_returnsEntry() throws Exception {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("X");
        when(ref.getUuid()).thenReturn(id);
        when(ref.getInfobaseType()).thenReturn(InfobaseType.FILE);
        FileConnectionString cs = mock(FileConnectionString.class);
        when(cs.asConnectionString()).thenReturn("");
        when(ref.getConnectionString()).thenReturn(cs);

        InfobaseRegistry r = mock(InfobaseRegistry.class);
        when(r.findByUuid(id)).thenReturn(Optional.of(ref));

        Map<String, Object> args = new HashMap<>();
        args.put("uuid", id.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) new GetInfobaseTool(r).call(args);
        assertEquals("X", entry.get("name"));
    }

    @Test
    public void call_notFound_throwsToolException() {
        InfobaseRegistry r = mock(InfobaseRegistry.class);
        when(r.findByName("Missing")).thenReturn(Optional.empty());
        Map<String, Object> args = new HashMap<>();
        args.put("name", "Missing");
        try { new GetInfobaseTool(r).call(args); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_neitherKey_throwsToolException() {
        try { new GetInfobaseTool(mock(InfobaseRegistry.class)).call(new HashMap<>()); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_invalidUuid_throwsToolException() {
        Map<String, Object> args = new HashMap<>();
        args.put("uuid", "not-a-uuid");
        try { new GetInfobaseTool(mock(InfobaseRegistry.class)).call(args); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }
}
