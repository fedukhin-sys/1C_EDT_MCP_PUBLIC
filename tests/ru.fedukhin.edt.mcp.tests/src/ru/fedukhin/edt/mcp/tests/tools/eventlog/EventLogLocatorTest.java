package ru.fedukhin.edt.mcp.tests.tools.eventlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.InfobaseType;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogLocator;

public class EventLogLocatorTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void fileInfobase_appendsLogDir() throws Exception {
        Path ibDir = tmp.newFolder("ib").toPath();
        Files.createDirectory(ibDir.resolve("1Cv8Log"));
        FileConnectionString fcs = mock(FileConnectionString.class);
        when(fcs.getFile()).thenReturn(ibDir.toString());
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getInfobaseType()).thenReturn(InfobaseType.FILE);
        when(ref.getConnectionString()).thenReturn(fcs);
        when(ref.getName()).thenReturn("Demo");

        EventLogLocator.ResolvedLog r = new EventLogLocator().locate(ref);
        assertEquals("FILE", r.infobaseType);
        assertTrue(r.exists);
        assertEquals(ibDir.resolve("1Cv8Log"), r.logDir);
        assertNull(r.clusterRef);
        assertNull(r.srvinfoDir);
    }

    @Test
    public void serverInfobase_resolvesViaSrvinfoOverride() throws Exception {
        // Synthetic srvinfo/reg_1541/<ibUuid>/1Cv8Log/  + 1CV8Clst.lst mapping ref→uuid
        Path srvinfo = tmp.newFolder("srvinfo").toPath();
        Path reg = srvinfo.resolve("reg_1541");
        Files.createDirectory(reg);
        String ibUuid = "11111111-1111-1111-1111-111111111111";
        Path ibDir = reg.resolve(ibUuid);
        Files.createDirectory(ibDir);
        Path logDir = ibDir.resolve("1Cv8Log");
        Files.createDirectory(logDir);

        // 1CV8Clst.lst is windows-1251; we use only ASCII inside the matched part.
        String lst = "{1,\n{0,0,\"X\",1},\n{4,\n{" + ibUuid + ",\"ESS\",\"\",\"PostgreSQL\"}\n}";
        Files.write(reg.resolve("1CV8Clst.lst"), lst.getBytes(Charset.forName("windows-1251")));

        ServerConnectionString scs = mock(ServerConnectionString.class);
        when(scs.getReference()).thenReturn("ESS");
        when(scs.getServer()).thenReturn("localhost");
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getInfobaseType()).thenReturn(InfobaseType.SERVER);
        when(ref.getConnectionString()).thenReturn(scs);
        when(ref.getName()).thenReturn("ЕСС");

        EventLogLocator.ResolvedLog r = new EventLogLocator().setSrvinfoDir(srvinfo).locate(ref);
        assertEquals("SERVER", r.infobaseType);
        assertEquals("ESS", r.clusterRef);
        assertEquals(ibUuid, r.clusterIbUuid);
        assertEquals(logDir, r.logDir);
        assertTrue(r.exists);
    }

    @Test(expected = ToolException.class)
    public void serverInfobase_missingRef_throws() throws Exception {
        Path srvinfo = tmp.newFolder("srvinfo").toPath();
        Path reg = srvinfo.resolve("reg_1541");
        Files.createDirectory(reg);
        Files.write(reg.resolve("1CV8Clst.lst"), "{1,{0,0,\"\"}}".getBytes(Charset.forName("windows-1251")));

        ServerConnectionString scs = mock(ServerConnectionString.class);
        when(scs.getReference()).thenReturn("MISSING");
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getInfobaseType()).thenReturn(InfobaseType.SERVER);
        when(ref.getConnectionString()).thenReturn(scs);
        when(ref.getName()).thenReturn("X");

        new EventLogLocator().setSrvinfoDir(srvinfo).locate(ref);
    }

    @Test
    public void listPartitions_returnsSortedByName() throws Exception {
        Path dir = tmp.newFolder("log").toPath();
        Files.write(dir.resolve("20260413000000.lgp"), new byte[10]);
        Files.write(dir.resolve("20260406000000.lgp"), new byte[20]);
        Files.write(dir.resolve("20260420000000.lgp"), new byte[5]);
        Files.write(dir.resolve("not-a-log.txt"), new byte[1]);

        List<Map<String, Object>> p = EventLogLocator.listPartitions(dir);
        assertEquals(3, p.size());
        assertEquals("20260406000000.lgp", p.get(0).get("file"));
        assertEquals("2026-04-06", p.get(0).get("date"));
        assertEquals(20L, p.get(0).get("sizeBytes"));
        assertEquals("20260420000000.lgp", p.get(2).get("file"));
    }

    @Test
    public void parsePartitionDate_handlesShortName() {
        assertNull(EventLogLocator.parsePartitionDate("x.lgp"));
        assertEquals("2026-04-06", EventLogLocator.parsePartitionDate("20260406000000.lgp"));
    }
}
