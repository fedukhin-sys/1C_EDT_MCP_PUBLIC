package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.InfobaseType;
import com._1c.g5.v8.dt.platform.version.Version;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.CreateInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

public class CreateInfobaseToolTest {

    @Test
    public void call_happy_callsRegistryAndReturnsEntry() throws Exception {
        InfobaseRegistry r = mock(InfobaseRegistry.class);
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        when(mgr.generateGroupName()).thenReturn("Auto");
        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);
        IRuntime rt = mock(IRuntime.class);
        when(rt.getVersion()).thenReturn(new Version("8.3.24"));
        when(rr.getRuntimes()).thenReturn(Arrays.asList(rt));

        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        when(ref.getUuid()).thenReturn(UUID.randomUUID());
        when(ref.getInfobaseType()).thenReturn(InfobaseType.FILE);
        FileConnectionString cs = mock(FileConnectionString.class);
        when(cs.asConnectionString()).thenReturn("File=\"C:/IB/Demo\";");
        when(ref.getConnectionString()).thenReturn(cs);
        when(r.createFileInfobase(eq("Demo"), any(Path.class), eq("8.3.24"), eq("Auto"),
                                  any(Duration.class))).thenReturn(ref);

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Demo");
        args.put("type", "FILE");
        args.put("location", "C:/IB/Demo");
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) new CreateInfobaseTool(r, mgr, rr).call(args);
        assertEquals("Demo", entry.get("name"));
        assertEquals("FILE", entry.get("type"));
    }

    @Test
    public void call_typeNotFile_throwsToolException() {
        Map<String, Object> args = new HashMap<>();
        args.put("name", "X"); args.put("type", "SERVER"); args.put("location", "C:/IB/X");
        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);
        when(rr.getRuntimes()).thenReturn(Collections.emptyList());
        try {
            new CreateInfobaseTool(mock(InfobaseRegistry.class), mock(IInfobaseManager.class), rr).call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_versionFromArgs_isUsed() throws Exception {
        InfobaseRegistry r = mock(InfobaseRegistry.class);
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        when(mgr.generateGroupName()).thenReturn("Auto");
        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);

        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Demo");
        when(ref.getUuid()).thenReturn(UUID.randomUUID());
        when(ref.getInfobaseType()).thenReturn(InfobaseType.FILE);
        FileConnectionString cs = mock(FileConnectionString.class);
        when(cs.asConnectionString()).thenReturn("");
        when(ref.getConnectionString()).thenReturn(cs);
        when(r.createFileInfobase(eq("Demo"), any(Path.class), eq("8.3.99"), eq("Auto"),
                                  any(Duration.class))).thenReturn(ref);

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Demo"); args.put("type", "FILE"); args.put("location", "C:/IB/Demo");
        args.put("version", "8.3.99");
        new CreateInfobaseTool(r, mgr, rr).call(args);
    }

    @Test
    public void call_registryThrows_propagatesToolException() throws Exception {
        InfobaseRegistry r = mock(InfobaseRegistry.class);
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        when(mgr.generateGroupName()).thenReturn("Auto");
        IRuntimeRegistry rr = mock(IRuntimeRegistry.class);
        IRuntime rt = mock(IRuntime.class); when(rt.getVersion()).thenReturn(new Version("8.3.24"));
        when(rr.getRuntimes()).thenReturn(Arrays.asList(rt));
        when(r.createFileInfobase(any(), any(), any(), any(), any()))
            .thenThrow(new ToolException("location 'C:/IB/Demo' is not empty"));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "Demo"); args.put("type", "FILE"); args.put("location", "C:/IB/Demo");
        try { new CreateInfobaseTool(r, mgr, rr).call(args); fail("expected ToolException"); }
        catch (ToolException e) { /* ok */ }
    }
}
