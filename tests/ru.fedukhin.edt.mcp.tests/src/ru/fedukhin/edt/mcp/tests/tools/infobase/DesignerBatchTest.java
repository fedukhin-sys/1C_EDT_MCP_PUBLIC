package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.DesignerBatch;

public class DesignerBatchTest {

    private static final File EXE = new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe");

    private static Process process(int exitCode, boolean finished, String stderr) {
        Process p = mock(Process.class);
        try {
            when(p.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(finished);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        when(p.exitValue()).thenReturn(exitCode);
        when(p.getErrorStream()).thenReturn(new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8)));
        when(p.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return p;
    }

    @Test
    public void run_appendsOutLogAndDisableStartupDialogs_andReadsOutText_thenDeletesLog() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        List<Path> logs = new ArrayList<>();
        DesignerBatch batch = new DesignerBatch((cmd, dir) -> {
            commands.add(new ArrayList<>(cmd));
            Path log = Paths.get(cmd.get(cmd.indexOf("/Out") + 1));
            logs.add(log);
            Files.write(log, "Загрузка конфигурации успешно завершена".getBytes(StandardCharsets.UTF_8));
            return process(0, true, "");
        }, Duration.ofMillis(10));

        DesignerBatch.Result result = batch.run(EXE, List.of("/F", "E:/ib"), new NullProgressMonitor(), null);

        assertEquals(0, result.exitCode());
        assertEquals("Загрузка конфигурации успешно завершена", result.output());
        List<String> cmd = commands.get(0);
        assertEquals(List.of(EXE.getAbsolutePath(), "DESIGNER", "/F", "E:/ib"), cmd.subList(0, 4));
        assertEquals("/DisableStartupDialogs", cmd.get(cmd.size() - 1));
        assertFalse("временный лог /Out удаляется после чтения", Files.exists(logs.get(0)));
    }

    @Test
    public void run_emptyOutLog_fallsBackToStderr() throws Exception {
        DesignerBatch batch = new DesignerBatch((cmd, dir) -> process(1, true, "нет доступа к каталогу"),
            Duration.ofMillis(10));

        DesignerBatch.Result result = batch.run(EXE, List.of(), null, null);

        assertEquals(1, result.exitCode());
        assertEquals("нет доступа к каталогу", result.output());
    }

    @Test(timeout = 20_000)
    public void run_timeout_destroysProcess() throws Exception {
        Process hung = process(0, false, "");
        DesignerBatch batch = new DesignerBatch((cmd, dir) -> hung, Duration.ofMillis(10));
        try {
            batch.run(EXE, List.of(), new NullProgressMonitor(), Duration.ofMillis(50));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("не завершился"));
        }
        verify(hung).destroyForcibly();
    }

    @Test
    public void run_cancelledBeforeStart_doesNotStartProcess() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        DesignerBatch batch = new DesignerBatch((cmd, dir) -> {
            commands.add(cmd);
            return process(0, true, "");
        }, Duration.ofMillis(10));
        NullProgressMonitor cancelled = new NullProgressMonitor();
        cancelled.setCanceled(true);
        try {
            batch.run(EXE, List.of(), cancelled, null);
            fail("expected OperationCanceledException");
        } catch (OperationCanceledException expected) {
            assertTrue(commands.isEmpty());
        }
    }

    @Test
    public void connection_fileAndServer_andUnsupported() throws Exception {
        InfobaseReference file = mock(InfobaseReference.class);
        FileConnectionString fcs = mock(FileConnectionString.class);
        when(fcs.getFile()).thenReturn("E:/ib/Demo");
        when(file.getConnectionString()).thenReturn(fcs);
        assertEquals(List.of("/F", "E:/ib/Demo"), DesignerBatch.connection(file));

        InfobaseReference server = mock(InfobaseReference.class);
        ServerConnectionString scs = mock(ServerConnectionString.class);
        when(scs.getServer()).thenReturn("srv:1541");
        when(scs.getReference()).thenReturn("demo");
        when(server.getConnectionString()).thenReturn(scs);
        assertEquals(List.of("/S", "srv:1541\\demo"), DesignerBatch.connection(server));

        InfobaseReference web = mock(InfobaseReference.class);
        IConnectionString wcs = mock(IConnectionString.class);
        when(wcs.asConnectionString()).thenReturn("ws=\"http://host/demo\";");
        when(web.getConnectionString()).thenReturn(wcs);
        when(web.getName()).thenReturn("Web");
        try {
            DesignerBatch.connection(web);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("файловые и серверные"));
        }
    }

    @Test
    public void appendCredentials_separateElements_passwordOnlyWithUser() {
        List<String> cmd = new ArrayList<>();
        DesignerBatch.appendCredentials(cmd, "Иванов Иван", "p w");
        assertEquals(List.of("/N", "Иванов Иван", "/P", "p w"), cmd);

        List<String> noPassword = new ArrayList<>();
        DesignerBatch.appendCredentials(noPassword, "Админ", "");
        assertEquals(List.of("/N", "Админ"), noPassword);

        List<String> noUser = new ArrayList<>();
        DesignerBatch.appendCredentials(noUser, null, "secret");
        assertTrue(noUser.isEmpty());
    }

    @Test
    public void poll_belowOneMillisecond_rejected() {
        try {
            new DesignerBatch((cmd, dir) -> null, Duration.ZERO);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("poll"));
        }
    }
}
