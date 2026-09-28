package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferenceException;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import com._1c.g5.v8.dt.platform.version.Version;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ServerInfobaseParams;

public class InfobaseRegistryTest {

    @Test
    public void listAll_filtersOutNonInfobaseSections() {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        Section group = mock(Section.class);   // non-InfobaseReference section, e.g. a Group
        when(mgr.getAll()).thenReturn(Arrays.asList(ref, group));

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        List<InfobaseReference> result = r.listAll();
        assertEquals(1, result.size());
        assertEquals(ref, result.get(0));
    }

    @Test
    public void findByName_delegates() {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(mgr.findInfobaseByName("X")).thenReturn(Optional.of(ref));

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        assertEquals(Optional.of(ref), r.findByName("X"));
    }

    @Test
    public void delete_callsManagerAndDoesNotTouchFilesByDefault() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        FileConnectionString cs = mock(FileConnectionString.class);
        Path tmp = Files.createTempDirectory("ib-delete-default");
        when(cs.getFile()).thenReturn(tmp.toString());
        when(ref.getConnectionString()).thenReturn(cs);

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        r.delete(ref, false);

        verify(mgr).delete(ref);
        assertTrue("dir must remain when deleteFiles=false", Files.exists(tmp));
        Files.deleteIfExists(tmp);
    }

    @Test
    public void delete_deleteFilesTrue_removesDirectory() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        FileConnectionString cs = mock(FileConnectionString.class);
        Path tmp = Files.createTempDirectory("ib-delete-files");
        Files.createFile(tmp.resolve("1Cv8.1CD"));
        when(cs.getFile()).thenReturn(tmp.toString());
        when(ref.getConnectionString()).thenReturn(cs);

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        r.delete(ref, true);

        verify(mgr).delete(ref);
        assertFalse("dir must be removed", Files.exists(tmp));
    }

    /**
     * {@code File::delete} возвращает false вместо исключения: если файл ИБ занят (открыт клиент
     * 1С), каталог остаётся на диске, а инструмент рапортует успех. Ошибка должна быть видимой.
     */
    @Test
    public void delete_fileLocked_throwsToolExceptionNamingLeftovers() throws Exception {
        Assume.assumeTrue("блокировка открытого файла — поведение Windows",
            System.getProperty("os.name", "").startsWith("Windows"));

        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        FileConnectionString cs = mock(FileConnectionString.class);
        Path tmp = Files.createTempDirectory("ib-delete-locked");
        Path locked = tmp.resolve("1Cv8.1CD");
        Files.createFile(locked);
        when(cs.getFile()).thenReturn(tmp.toString());
        when(ref.getConnectionString()).thenReturn(cs);

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        // Именно java.io: NIO-каналы на Windows открывают файл с FILE_SHARE_DELETE,
        // и такой «занятый» файл всё равно удалится — блокировку даёт только FileOutputStream.
        try (OutputStream hold = new FileOutputStream(locked.toFile())) {
            r.delete(ref, true);
            fail("expected ToolException — занятый файл не удалился");
        } catch (ToolException e) {
            assertTrue("сообщение должно называть неудалённый файл, было: " + e.getMessage(),
                e.getMessage().contains("1Cv8.1CD"));
        } finally {
            Files.deleteIfExists(locked);
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void delete_managerThrows_propagatedAsToolException() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        org.mockito.Mockito.doThrow(new InfobaseReferenceException("in use")).when(mgr).delete(ref);

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        try {
            r.delete(ref, false);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("in use"));
        }
    }

    @Test
    public void createFileInfobase_locationNotEmpty_throwsBeforeProcess() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        RuntimeCli cli = mock(RuntimeCli.class);
        Path tmp = Files.createTempDirectory("ib-not-empty");
        Files.createFile(tmp.resolve("dummy"));

        InfobaseRegistry r = new InfobaseRegistry(mgr, cli);
        try {
            r.createFileInfobase("X", tmp, "8.3.24", "Demo", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("not empty"));
        }
        verify(cli, never()).createFileInfobase(any(), any(), any());
        Files.deleteIfExists(tmp.resolve("dummy"));
        Files.deleteIfExists(tmp);
    }

    @Test
    public void createFileInfobase_happy_callsCliAndAddsToManager() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        RuntimeCli cli = mock(RuntimeCli.class);
        Path tmp = Files.createTempDirectory("ib-happy-parent").resolve("Demo");
        // tmp does NOT yet exist — registry must allow this.

        InfobaseRegistry r = new InfobaseRegistry(mgr, cli);
        InfobaseReference ref = r.createFileInfobase("Demo", tmp, "8.3.24", "DemoGroup", Duration.ofSeconds(60));

        verify(cli).createFileInfobase(tmp, "8.3.24", Duration.ofSeconds(60));
        verify(mgr).add(ref, "DemoGroup");
        assertEquals("Demo", ref.getName());
        assertEquals(UUID.class, ref.getUuid().getClass());
    }

    private static ServerInfobaseParams serverParams() {
        return new ServerInfobaseParams("localhost", "DemoRef", "PostgreSQL", "localhost", "demo_db",
            "postgres", null, true, true, null, null);
    }

    @Test
    public void createFileInfobase_withUuid_usesGivenUuidAndRootGroup() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        RuntimeCli cli = mock(RuntimeCli.class);
        Path location = Files.createTempDirectory("ib-uuid-parent").resolve("Demo");
        UUID uuid = UUID.randomUUID();

        InfobaseReference ref = new InfobaseRegistry(mgr, cli)
            .createFileInfobase("Demo", uuid, location, "8.3.27", null, Duration.ofSeconds(60));

        verify(cli).createFileInfobase(location, "8.3.27", Duration.ofSeconds(60));
        verify(mgr).add(ref, null);
        assertEquals(uuid, ref.getUuid());
        assertTrue(ref.getConnectionString() instanceof FileConnectionString);
    }

    @Test
    public void createServerInfobase_registersServerConnection() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        RuntimeCli cli = mock(RuntimeCli.class);
        ServerInfobaseParams params = serverParams();
        UUID uuid = UUID.randomUUID();

        InfobaseReference ref = new InfobaseRegistry(mgr, cli)
            .createServerInfobase("Demo", uuid, params, "8.3.27.2214", null, Duration.ofSeconds(60));

        verify(cli).createServerInfobase(params, "8.3.27.2214", Duration.ofSeconds(60));
        verify(mgr).add(ref, null);
        assertEquals("Demo", ref.getName());
        assertEquals(uuid, ref.getUuid());
        assertEquals("8.3.27.2214", ref.getVersion());
        assertTrue(ref.getConnectionString() instanceof ServerConnectionString);
        ServerConnectionString cs = (ServerConnectionString) ref.getConnectionString();
        assertEquals("localhost", cs.getServer());
        assertEquals("DemoRef", cs.getReference());
    }

    /**
     * L4 сквозь настоящий {@link RuntimeCli}: CREATEINFOBASE прошёл, но проверка тем же 1cv8 показала,
     * что в «новой» базе уже есть конфигурация (1С подключила её к существующей БД) — в EDT ничего не
     * регистрируется, загрузка .dt до неё не доходит.
     */
    @Test
    public void createServerInfobase_existingDatabase_nothingRegistered() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        RuntimeCli cli = new RuntimeCli(runtimes("8.3.27"), v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"),
            probeReturning(0, "<ConfigDumpInfo><ConfigVersions>"
                + "<Metadata name=\"Configuration.Рабочая\" id=\"1\" configVersion=\"2\"/></ConfigVersions></ConfigDumpInfo>"));
        try {
            new InfobaseRegistry(mgr, cli)
                .createServerInfobase("Demo", UUID.randomUUID(), serverParams(), "8.3.27", null, Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("'demo_db'"));
        }
        verify(mgr, never()).add(any(), any());
    }

    @Test
    public void createServerInfobase_newEmptyDatabase_registered() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        RuntimeCli cli = new RuntimeCli(runtimes("8.3.27"), v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"),
            probeReturning(0, "<ConfigDumpInfo><ConfigVersions/></ConfigDumpInfo>"));

        InfobaseReference ref = new InfobaseRegistry(mgr, cli)
            .createServerInfobase("Demo", UUID.randomUUID(), serverParams(), "8.3.27", null, Duration.ofSeconds(60));

        verify(mgr).add(ref, null);
    }

    private static IRuntimeRegistry runtimes(String version) {
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getVersion()).thenReturn(new Version(version));
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntimes()).thenReturn(Collections.singletonList(runtime));
        return reg;
    }

    /** CREATEINFOBASE успешен; проверочная выгрузка кладёт {@code dump} в каталог и выходит с {@code probeExit}. */
    private static RuntimeCli.ProcessFactory probeReturning(int probeExit, String dump) {
        return (cmd, dir) -> {
            int code = 0;
            if (!"CREATEINFOBASE".equals(cmd.get(1))) {
                Path target = Path.of(cmd.get(cmd.indexOf("/DumpConfigToFiles") + 1));
                Files.createDirectories(target);
                Files.write(target.resolve("ConfigDumpInfo.xml"), dump.getBytes(StandardCharsets.UTF_8));
                code = probeExit;
            }
            Process p = mock(Process.class);
            try {
                when(p.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            when(p.exitValue()).thenReturn(code);
            when(p.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(p.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            return p;
        };
    }

    @Test
    public void createServerInfobase_cliFailure_registersNothing() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        RuntimeCli cli = mock(RuntimeCli.class);
        when(cli.createServerInfobase(any(), any(), any()))
            .thenThrow(new ToolException("1cv8 CREATEINFOBASE exited 1: нет доступа к СУБД"));

        try {
            new InfobaseRegistry(mgr, cli)
                .createServerInfobase("Demo", UUID.randomUUID(), serverParams(), "8.3.27", null, Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("СУБД"));
        }
        verify(mgr, never()).add(any(), any());
    }
}
