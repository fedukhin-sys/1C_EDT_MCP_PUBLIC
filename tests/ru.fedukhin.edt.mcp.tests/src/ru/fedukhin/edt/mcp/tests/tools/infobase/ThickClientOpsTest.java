package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com._1c.g5.designer.ssh.client.operation.IDbUpdateConfirm;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IDesignerSessionThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.DesignerBatch;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps;

public class ThickClientOpsTest {

    private final IProject project = mock(IProject.class);
    private final InfobaseReference ref = mock(InfobaseReference.class);
    private final ILaunchableRuntimeComponent component = mock(ILaunchableRuntimeComponent.class);
    private final IThickClientLauncher launcher = mock(IThickClientLauncher.class);
    private final IInfobaseAccessManager access = mock(IInfobaseAccessManager.class);
    private final IInfobaseManager infobases = mock(IInfobaseManager.class);
    private final ReentrantLock lock = new ReentrantLock();

    private ThickClientOps ops() {
        when(ref.getName()).thenReturn("Demo");
        when(infobases.getLock(ref)).thenReturn(lock);
        return new ThickClientOps((p, ib) -> new ThickClientOps.Launcher(component, launcher), access, infobases);
    }

    /** С коротким интервалом проверки отмены, пока замок базы держит кто-то другой. */
    private ThickClientOps ops(Duration lockPoll) {
        when(ref.getName()).thenReturn("Demo");
        when(infobases.getLock(ref)).thenReturn(lock);
        return new ThickClientOps((p, ib) -> new ThickClientOps.Launcher(component, launcher), access, infobases,
            lockPoll);
    }

    @Test
    public void restoreDt_runsUnderInfobaseLock_withMonitorAndStoredCredentials() throws Exception {
        when(access.resolveSettings(ref))
            .thenReturn(new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "Админ", "secret", null));
        IProgressMonitor monitor = new NullProgressMonitor();
        Path dt = Paths.get("E:/dumps/demo.dt");
        AtomicBoolean heldDuringCall = new AtomicBoolean();
        ThickClientOps ops = ops();
        doAnswer(inv -> { heldDuringCall.set(lock.isHeldByCurrentThread()); return null; })
            .when(launcher).importDtToInfobase(eq(component), eq(ref), any(RuntimeExecutionArguments.class), eq(dt));

        ops.restoreDt(project, ref, dt, monitor);

        ArgumentCaptor<RuntimeExecutionArguments> args = ArgumentCaptor.forClass(RuntimeExecutionArguments.class);
        verify(launcher).importDtToInfobase(eq(component), eq(ref), args.capture(), eq(dt));
        assertTrue("операция обязана идти под замком базы EDT", heldDuringCall.get());
        assertFalse("замок снимается после операции", lock.isLocked());
        assertSame(monitor, args.getValue().getMonitor());
        assertEquals("Админ", args.getValue().getUsername());
        assertEquals("secret", args.getValue().getPassword());
        assertEquals(InfobaseAccess.INFOBASE, args.getValue().getAccess());
    }

    @Test
    public void backupDt_exportsToGivenFile() throws Exception {
        Path dt = Paths.get("E:/dumps/backup.dt");

        ops().backupDt(project, ref, dt, new NullProgressMonitor());

        verify(launcher).exportDtFromInfobase(eq(component), eq(ref), any(RuntimeExecutionArguments.class), eq(dt));
    }

    /**
     * Живой прогон 1.24.0: {@code importCfToInfobase} EDT строит {@code /LoadCfg <файл>} без
     * {@code -Extension} — {@code .cfe} грузился как ОСНОВНАЯ конфигурация («Ожидается файл
     * конфигурации»). Теперь — пакетный конфигуратор той установки, что EDT выбрала для базы, после
     * закрытия сеанса агента EDT, под замком базы; учётка — из настроек доступа, аргументы — отдельными
     * элементами, как у самой EDT (имя с пробелом дойдёт целым).
     */
    @Test
    public void loadExtension_fileInfobase_runsDesignerBatchWithExtension_afterClosingAgentSession() throws Exception {
        IDesignerSessionThickClientLauncher agent = mock(IDesignerSessionThickClientLauncher.class);
        AtomicBoolean closed = new AtomicBoolean();
        doAnswer(inv -> { closed.set(true); return null; })
            .when(agent).closeDesignerSession(eq(component), eq(ref), any(RuntimeExecutionArguments.class));
        when(component.getFile()).thenReturn(new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe"));
        fileConnection("E:/ib/Demo");
        when(access.resolveSettings(ref))
            .thenReturn(new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "Иванов Иван", "p@ss w0rd", null));
        FakeDesigner designer = new FakeDesigner();
        AtomicBoolean closedBeforeStart = new AtomicBoolean();
        AtomicBoolean lockedDuringRun = new AtomicBoolean();
        designer.onStart = () -> {
            closedBeforeStart.set(closed.get());
            lockedDuringRun.set(lock.isHeldByCurrentThread());
        };
        Path cfe = Paths.get("E:/ext/Beta_Склад.cfe");

        batchOps(agent, designer).loadExtension(project, ref, cfe, "Beta_Склад", new NullProgressMonitor());

        assertEquals(1, designer.commands.size());
        List<String> cmd = designer.commands.get(0);
        assertEquals(new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe").getAbsolutePath(), cmd.get(0));
        assertEquals("DESIGNER", cmd.get(1));
        assertEquals(List.of("/F", "E:/ib/Demo"), cmd.subList(2, 4));
        assertEquals(List.of("/LoadCfg", cfe.toString(), "-Extension", "Beta_Склад"), cmd.subList(4, 8));
        // Доступ — как у исполнителя EDT (appendInfobaseAccess): /WA -, затем /N и /P, всё отдельными элементами.
        assertEquals(List.of("/WA", "-", "/N", "Иванов Иван", "/P", "p@ss w0rd"), cmd.subList(8, 14));
        int out = cmd.indexOf("/Out");
        assertTrue("нужен /Out <лог>: " + cmd, out > 0 && out + 1 < cmd.size());
        assertTrue(cmd.contains("/DisableStartupDialogs"));
        assertTrue("сеанс агента EDT закрывается ДО запуска пакетного конфигуратора", closedBeforeStart.get());
        assertTrue("пакетный конфигуратор работает под замком базы EDT", lockedDuringRun.get());
        assertFalse("замок снимается после операции", lock.isLocked());
        verify(agent, never()).importCfToInfobase(any(), any(), any(), any());
    }

    @Test
    public void loadExtension_serverInfobase_connectsWithSlashS() throws Exception {
        when(component.getFile()).thenReturn(new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe"));
        ServerConnectionString cs = mock(ServerConnectionString.class);
        when(cs.getServer()).thenReturn("localhost");
        when(cs.getReference()).thenReturn("mcp_srv");
        when(ref.getConnectionString()).thenReturn(cs);
        FakeDesigner designer = new FakeDesigner();

        batchOps(launcher, designer).loadExtension(project, ref, Paths.get("E:/ext/Ext.cfe"), "Ext",
            new NullProgressMonitor());

        List<String> cmd = designer.commands.get(0);
        assertEquals(List.of("/S", "localhost\\mcp_srv"), cmd.subList(2, 4));
        assertFalse("без сохранённой учётки /N и /P не передаются: " + cmd, cmd.contains("/N") || cmd.contains("/P"));
        verify(launcher, never()).importCfToInfobase(any(), any(), any(), any());
    }

    /** Доступ по учётной записи ОС — как у EDT: /WA +, ни /N, ни /P. */
    @Test
    public void loadExtension_osAuthentication_passesNoCredentials() throws Exception {
        when(component.getFile()).thenReturn(new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe"));
        fileConnection("E:/ib/Demo");
        when(access.resolveSettings(ref))
            .thenReturn(new InfobaseAccessSettings(InfobaseAccess.OS, "DOMAIN\\user", "", null));
        FakeDesigner designer = new FakeDesigner();

        batchOps(launcher, designer).loadExtension(project, ref, Paths.get("E:/ext/Ext.cfe"), "Ext",
            new NullProgressMonitor());

        List<String> cmd = designer.commands.get(0);
        assertFalse(cmd.toString(), cmd.contains("/N") || cmd.contains("/P"));
        int wa = cmd.indexOf("/WA");
        assertTrue("нужен /WA +: " + cmd, wa > 0 && "+".equals(cmd.get(wa + 1)));
    }

    /**
     * Ревью fix round 2: «Дополнительные параметры» базы (код разрешения {@code /UC…}, разделители
     * {@code /Z…}) исполнитель EDT передаёт в каждую операцию (appendAdditionalParameters): берёт их у
     * зарегистрированной базы по UUID, режет по пробелам, снимает кавычки. Без них база с кодом
     * разрешения не пустит пакетный конфигуратор, а разделённая загрузит расширение не в тот контекст.
     */
    @Test
    public void loadExtension_passesAdditionalParametersOfRegisteredInfobase_likeEdt() throws Exception {
        when(component.getFile()).thenReturn(new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe"));
        fileConnection("E:/ib/Demo");
        java.util.UUID uuid = java.util.UUID.randomUUID();
        when(ref.getUuid()).thenReturn(uuid);
        when(ref.getAdditionalParameters()).thenReturn("/UCустаревший");
        InfobaseReference registered = mock(InfobaseReference.class);
        when(registered.getAdditionalParameters()).thenReturn("/UCКод123  /Z\"1,2\"");
        when(infobases.findInfobaseByUuid(uuid)).thenReturn(java.util.Optional.of(registered));
        FakeDesigner designer = new FakeDesigner();

        batchOps(launcher, designer).loadExtension(project, ref, Paths.get("E:/ext/Ext.cfe"), "Ext",
            new NullProgressMonitor());

        List<String> cmd = designer.commands.get(0);
        int ext = cmd.indexOf("-Extension");
        assertEquals(List.of("/UCКод123", "/Z1,2"), cmd.subList(ext + 2, ext + 4));
        assertFalse("параметры — зарегистрированной базы, а не переданной копии: " + cmd, cmd.contains("/UCустаревший"));
    }

    /**
     * Ревью fix round 2: строка подключения проверяется ДО закрытия сеанса агента — неподдерживаемая
     * база (веб) не должна лишаться агента EDT зря.
     */
    @Test
    public void loadExtension_unsupportedInfobase_failsBeforeClosingAgentSession() throws Exception {
        IDesignerSessionThickClientLauncher agent = mock(IDesignerSessionThickClientLauncher.class);
        when(component.getFile()).thenReturn(new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe"));
        com._1c.g5.v8.dt.platform.services.model.IConnectionString web =
            mock(com._1c.g5.v8.dt.platform.services.model.IConnectionString.class);
        when(web.asConnectionString()).thenReturn("ws=\"http://host/demo\";");
        when(ref.getConnectionString()).thenReturn(web);
        FakeDesigner designer = new FakeDesigner();
        try {
            batchOps(agent, designer).loadExtension(project, ref, Paths.get("E:/ext/Ext.cfe"), "Ext",
                new NullProgressMonitor());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("файловые и серверные"));
        }
        verify(agent, never()).closeDesignerSession(any(), any(), any());
        assertTrue(designer.commands.isEmpty());
        assertFalse(lock.isLocked());
    }

    /**
     * Ревью fix round 2: у пакетной загрузки .cfe свой предел времени — зависший 1cv8 рядом с
     * незакрытым агентом не держит замок базы EDT до лимита всего задания.
     */
    @Test(timeout = 20_000)
    public void loadExtension_hungDesigner_stoppedAtItsOwnTimeCap() throws Exception {
        when(component.getFile()).thenReturn(new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe"));
        fileConnection("E:/ib/Demo");
        FakeDesigner designer = new FakeDesigner();
        designer.finished = false;
        when(ref.getName()).thenReturn("Demo");
        when(infobases.getLock(ref)).thenReturn(lock);
        ThickClientOps ops = new ThickClientOps((p, ib) -> new ThickClientOps.Launcher(component, launcher), access,
            infobases, Duration.ofMillis(50), new DesignerBatch(designer, Duration.ofMillis(10)), Duration.ofMillis(60));
        try {
            ops.loadExtension(project, ref, Paths.get("E:/ext/Ext.cfe"), "Ext", new NullProgressMonitor());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("загрузка расширения Ext"));
            assertTrue(e.getMessage(), e.getMessage().contains("не завершился"));
            assertTrue(e.getMessage(), e.getMessage().contains("list_running_clients"));
        }
        verify(designer.process).destroyForcibly();
        assertFalse(lock.isLocked());
    }

    @Test
    public void cfeLoadTimeout_belowOneMillisecond_rejected() {
        try {
            new ThickClientOps((p, ib) -> new ThickClientOps.Launcher(component, launcher), access, infobases,
                Duration.ofMillis(50), new DesignerBatch((cmd, dir) -> null, Duration.ofMillis(10)), Duration.ZERO);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cfeLoadTimeout"));
        }
    }

    /**
     * Отказ конфигуратора: причина из /Out, пароль ИБ вырезан (и буквально, и после /P), подсказка про
     * сеансы — загрузка расширения монопольна.
     */
    @Test
    public void loadExtension_designerFailure_reportsOutTextWithoutPassword_andSessionsHint() throws Exception {
        when(component.getFile()).thenReturn(new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe"));
        fileConnection("E:/ib/Demo");
        when(access.resolveSettings(ref))
            .thenReturn(new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "Админ", "S3cr3tPw", null));
        FakeDesigner designer = new FakeDesigner();
        designer.exitCode = 1;
        designer.outText = "Ошибка загрузки расширения: база заблокирована (DESIGNER /N Админ /P S3cr3tPw /UCКод123)";
        try {
            batchOps(launcher, designer).loadExtension(project, ref, Paths.get("E:/ext/Ext.cfe"), "Ext",
                new NullProgressMonitor());
            fail("expected ToolException");
        } catch (ToolException e) {
            String m = e.getMessage();
            assertTrue(m, m.contains("загрузка расширения Ext"));
            assertTrue(m, m.contains("база заблокирована"));
            assertFalse(m, m.contains("S3cr3tPw"));
            assertFalse("код разрешения /UC тоже вырезается: " + m, m.contains("Код123"));
            assertTrue("монопольной операции нужна подсказка про сеансы: " + m, m.contains("list_running_clients"));
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertFalse(String.valueOf(t), String.valueOf(t).contains("S3cr3tPw"));
            }
        }
        assertFalse(lock.isLocked());
    }

    /** Отмена задания, пока идёт пакетный конфигуратор: процесс убивается, замок базы снимается. */
    @Test(timeout = 20_000)
    public void loadExtension_cancelledWhileDesignerRuns_destroysProcess() throws Exception {
        when(component.getFile()).thenReturn(new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe"));
        fileConnection("E:/ib/Demo");
        IProgressMonitor monitor = new NullProgressMonitor();
        FakeDesigner designer = new FakeDesigner();
        designer.finished = false;
        designer.onWait = () -> monitor.setCanceled(true);
        try {
            batchOps(launcher, designer).loadExtension(project, ref, Paths.get("E:/ext/Ext.cfe"), "Ext", monitor);
            fail("expected OperationCanceledException");
        } catch (OperationCanceledException expected) {
            // отмена задания
        }
        verify(designer.process).destroyForcibly();
        assertFalse(lock.isLocked());
    }

    /**
     * Закрыть сеанс агента не удалось — пакетный конфигуратор всё равно запускается: держит агент базу
     * или нет, скажет его собственный отказ; причина сбоя закрытия — в тексте ошибки.
     */
    @Test
    public void loadExtension_agentSessionCloseFailure_stillRunsDesigner_andIsReportedOnFailure() throws Exception {
        IDesignerSessionThickClientLauncher agent = mock(IDesignerSessionThickClientLauncher.class);
        doThrow(new RuntimeExecutionException("агент не отвечает"))
            .when(agent).closeDesignerSession(any(), any(), any());
        when(component.getFile()).thenReturn(new File("C:/1cv8/8.5.1.1423/bin/1cv8.exe"));
        fileConnection("E:/ib/Demo");
        FakeDesigner designer = new FakeDesigner();
        designer.exitCode = 1;
        designer.outText = "База данных заблокирована";
        try {
            batchOps(agent, designer).loadExtension(project, ref, Paths.get("E:/ext/Ext.cfe"), "Ext",
                new NullProgressMonitor());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("База данных заблокирована"));
            assertTrue(e.getMessage(), e.getMessage().contains("агент не отвечает"));
        }
        assertEquals("пакетный конфигуратор запущен, несмотря на сбой закрытия сеанса", 1, designer.commands.size());
    }

    /**
     * Fix round 7: «шлюз» EDT перед забором изменений базы — сеанс агента конфигуратора закрывается публичным
     * {@code closeDesignerSession}, как перед загрузкой .cfe: под внутрипроцессным замком базы, с тем же
     * исполнителем и аргументами доступа.
     */
    @Test
    public void releaseDesignerSession_closesAgentSessionUnderInfobaseLock_withStoredAccess() throws Exception {
        IDesignerSessionThickClientLauncher agent = mock(IDesignerSessionThickClientLauncher.class);
        when(access.resolveSettings(ref))
            .thenReturn(new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "Админ", "secret", null));
        AtomicBoolean lockedDuringClose = new AtomicBoolean();
        doAnswer(inv -> { lockedDuringClose.set(lock.isHeldByCurrentThread()); return null; })
            .when(agent).closeDesignerSession(eq(component), eq(ref), any(RuntimeExecutionArguments.class));
        IProgressMonitor monitor = new NullProgressMonitor();

        String failure = batchOps(agent, new FakeDesigner()).releaseDesignerSession(project, ref, monitor);

        assertNull(failure);
        ArgumentCaptor<RuntimeExecutionArguments> args = ArgumentCaptor.forClass(RuntimeExecutionArguments.class);
        verify(agent).closeDesignerSession(eq(component), eq(ref), args.capture());
        assertTrue("закрытие — под замком базы EDT", lockedDuringClose.get());
        assertFalse("замок снимается после операции", lock.isLocked());
        assertEquals("Админ", args.getValue().getUsername());
        assertEquals(InfobaseAccess.INFOBASE, args.getValue().getAccess());
        assertSame(monitor, args.getValue().getMonitor());
    }

    /** Исполнитель без агента конфигуратора: сеанса нет — и шлюза у EDT для него нет; успех без вызовов. */
    @Test
    public void releaseDesignerSession_withoutAgentLauncher_isNoOpSuccess() throws Exception {
        String failure = ops().releaseDesignerSession(project, ref, new NullProgressMonitor());

        assertNull(failure);
        verifyZeroInteractions(launcher);
        assertFalse(lock.isLocked());
    }

    /** Сбой закрытия — не исключение, а причина; пароль ИБ из текста вырезан. */
    @Test
    public void releaseDesignerSession_closeFailure_returnsReasonWithoutPassword() throws Exception {
        IDesignerSessionThickClientLauncher agent = mock(IDesignerSessionThickClientLauncher.class);
        when(access.resolveSettings(ref))
            .thenReturn(new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "Админ", "S3cr3t", null));
        doThrow(new RuntimeExecutionException("агент не отвечает: 1cv8 DESIGNER /N Админ /P S3cr3t"))
            .when(agent).closeDesignerSession(any(), any(), any());

        String failure = batchOps(agent, new FakeDesigner()).releaseDesignerSession(project, ref,
            new NullProgressMonitor());

        assertNotNull(failure);
        assertTrue(failure, failure.contains("агент не отвечает"));
        assertFalse(failure, failure.contains("S3cr3t"));
        assertFalse(lock.isLocked());
    }

    /** Fix round 8: отмена изнутри {@code closeDesignerSession} — отмена, а не «причина»; замок снят. */
    @Test
    public void releaseDesignerSession_cancelledInsideClose_propagatesCancellation() throws Exception {
        IDesignerSessionThickClientLauncher agent = mock(IDesignerSessionThickClientLauncher.class);
        doThrow(new OperationCanceledException("отменено в Progress view"))
            .when(agent).closeDesignerSession(any(), any(), any());
        try {
            batchOps(agent, new FakeDesigner()).releaseDesignerSession(project, ref, new NullProgressMonitor());
            fail("expected OperationCanceledException");
        } catch (OperationCanceledException expected) {
            // отмена дошла до вызывающего
        }
        assertFalse(lock.isLocked());
    }

    /** Исполнитель не нашёлся (нет установки) — тоже причина, без исключения. */
    @Test
    public void releaseDesignerSession_resolverFailure_returnsReason() throws Exception {
        when(ref.getName()).thenReturn("Demo");
        when(infobases.getLock(ref)).thenReturn(lock);
        ThickClientOps ops = new ThickClientOps((p, ib) -> {
            throw new ToolException("не найдена установка 1С:Предприятия для базы Demo");
        }, access, infobases);

        String failure = ops.releaseDesignerSession(project, ref, new NullProgressMonitor());

        assertTrue(String.valueOf(failure), String.valueOf(failure).contains("не найдена установка"));
        assertFalse(lock.isLocked());
    }

    /** Отмена задания, пока замок базы держит кто-то другой, — не «причина», а отмена. */
    @Test(timeout = 20_000)
    public void releaseDesignerSession_cancelledWhileWaitingForLock_propagatesCancellation() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                locked.countDown();
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "infobase-lock-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue("поток-держатель не взял замок", locked.await(5, TimeUnit.SECONDS));
        IDesignerSessionThickClientLauncher agent = mock(IDesignerSessionThickClientLauncher.class);
        IProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        try {
            batchOps(agent, new FakeDesigner()).releaseDesignerSession(project, ref, monitor);
            fail("expected OperationCanceledException");
        } catch (OperationCanceledException expected) {
            // отмена дошла до вызывающего
        } finally {
            release.countDown();
        }
        verify(agent, never()).closeDesignerSession(any(), any(), any());
        holder.join(5000);
    }

    /** r1: интервал ожидания замка — не {@code null} и не меньше 1 мс (иначе {@code tryLock(0)} крутится вхолостую). */
    @Test
    public void lockPoll_nullOrBelowOneMillisecond_rejected() {
        ThickClientOps.LauncherResolver resolver = (p, ib) -> new ThickClientOps.Launcher(component, launcher);
        try {
            new ThickClientOps(resolver, access, infobases, null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertTrue(String.valueOf(expected.getMessage()), String.valueOf(expected.getMessage()).contains("lockPoll"));
        }
        for (Duration tooShort : List.of(Duration.ZERO, Duration.ofNanos(999_999), Duration.ofMillis(-5))) {
            try {
                new ThickClientOps(resolver, access, infobases, tooShort);
                fail("expected IllegalArgumentException for " + tooShort);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("lockPoll"));
            }
        }
        new ThickClientOps(resolver, access, infobases, Duration.ofMillis(1));
    }

    private void fileConnection(String dir) {
        FileConnectionString cs = mock(FileConnectionString.class);
        when(cs.getFile()).thenReturn(dir);
        when(ref.getConnectionString()).thenReturn(cs);
    }

    /** ThickClientOps с пакетным конфигуратором-заглушкой и заданным исполнителем EDT. */
    private ThickClientOps batchOps(IThickClientLauncher executor, FakeDesigner designer) {
        when(ref.getName()).thenReturn("Demo");
        when(infobases.getLock(ref)).thenReturn(lock);
        return new ThickClientOps((p, ib) -> new ThickClientOps.Launcher(component, executor), access, infobases,
            Duration.ofMillis(50), new DesignerBatch(designer, Duration.ofMillis(10)));
    }

    /** Заглушка 1cv8: запоминает команду, пишет текст в лог /Out и «завершается» с заданным кодом. */
    private static final class FakeDesigner implements RuntimeCli.ProcessFactory {
        final List<List<String>> commands = new ArrayList<>();
        int exitCode;
        String outText = "";
        boolean finished = true;
        Runnable onStart = () -> {};
        Runnable onWait = () -> {};
        Process process;

        @Override
        public Process start(List<String> command, File workingDir) throws IOException {
            commands.add(new ArrayList<>(command));
            onStart.run();
            int out = command.indexOf("/Out");
            if (out < 0 || out + 1 >= command.size()) throw new AssertionError("нет /Out <лог>: " + command);
            Files.write(Paths.get(command.get(out + 1)), outText.getBytes(StandardCharsets.UTF_8));
            Process p = mock(Process.class);
            try {
                when(p.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
                    onWait.run();
                    return finished;
                });
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            when(p.exitValue()).thenReturn(exitCode);
            when(p.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(p.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            process = p;
            return p;
        }
    }

    @Test
    public void applyExtension_autoConfirmsRestructure_forNamedExtension() throws Exception {
        ThickClientOps ops = ops();
        when(launcher.updateDatabaseConfiguration(eq(component), eq(ref), any(), any())).thenReturn(true);

        ops.applyExtension(project, ref, "Ext", new NullProgressMonitor());

        ArgumentCaptor<IDbUpdateConfirm> confirm = ArgumentCaptor.forClass(IDbUpdateConfirm.class);
        ArgumentCaptor<RuntimeExecutionArguments> args = ArgumentCaptor.forClass(RuntimeExecutionArguments.class);
        verify(launcher).updateDatabaseConfiguration(eq(component), eq(ref), confirm.capture(), args.capture());
        assertTrue("реструктуризация подтверждается без вопросов", confirm.getValue().confirm(List.of()));
        assertEquals("Ext", args.getValue().getExtensionName());
    }

    @Test
    public void applyExtension_refusedByDesigner_throws() throws Exception {
        ThickClientOps ops = ops();
        when(launcher.updateDatabaseConfiguration(any(), any(), any(), any())).thenReturn(false);
        try {
            ops.applyExtension(project, ref, "Ext", new NullProgressMonitor());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Ext"));
            assertTrue("применение расширения — монопольная операция, нужна подсказка про сеансы: "
                + e.getMessage(), e.getMessage().contains("list_running_clients"));
        }
    }

    @Test
    public void launcherFailure_isWrappedWithOperation_andLockReleased() throws Exception {
        ThickClientOps ops = ops();
        doThrow(new RuntimeExecutionException("Файл не найден"))
            .when(launcher).importDtToInfobase(any(), any(), any(), any());
        try {
            ops.restoreDt(project, ref, Paths.get("E:/dumps/none.dt"), new NullProgressMonitor());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(".dt"));
            assertTrue(e.getMessage(), e.getMessage().contains("Файл не найден"));
            assertTrue("монопольной операции нужна подсказка про сеансы: " + e.getMessage(),
                e.getMessage().contains("list_running_clients"));
        }
        assertFalse(lock.isLocked());
    }

    @Test
    public void launcherFailure_masksStoredPassword() throws Exception {
        when(access.resolveSettings(ref))
            .thenReturn(new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "Админ", "S3cr3t", null));
        ThickClientOps ops = ops();
        doThrow(new RuntimeExecutionException("login Админ/S3cr3t refused"))
            .when(launcher).importDtToInfobase(any(), any(), any(), any());
        try {
            ops.restoreDt(project, ref, Paths.get("E:/dumps/none.dt"), new NullProgressMonitor());
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("***"));
            assertFalse(e.getMessage(), e.getMessage().contains("S3cr3t"));
        }
    }

    /**
     * Причина ToolException уходит в лог EDT целиком (McpJobs.log пишет всю цепочку): там не должно
     * быть ни исходного исключения исполнителя с паролем в тексте, ни его собственных причин.
     */
    @Test
    public void launcherFailure_causeChainCarriesNoPassword() throws Exception {
        when(access.resolveSettings(ref))
            .thenReturn(new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "Админ", "S3cr3t", null));
        ThickClientOps ops = ops();
        RuntimeExecutionException raw = new RuntimeExecutionException("login Админ/S3cr3t refused",
            new IllegalStateException("1cv8 DESIGNER /N Админ /P S3cr3t"));
        doThrow(raw).when(launcher).importDtToInfobase(any(), any(), any(), any());
        try {
            ops.restoreDt(project, ref, Paths.get("E:/dumps/none.dt"), new NullProgressMonitor());
            fail("expected ToolException");
        } catch (ToolException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertFalse(t.toString(), String.valueOf(t.toString()).contains("S3cr3t"));
                assertFalse(String.valueOf(t.getMessage()), String.valueOf(t.getMessage()).contains("S3cr3t"));
            }
            StringWriter printed = new StringWriter();
            e.printStackTrace(new PrintWriter(printed));
            assertFalse(printed.toString(), printed.toString().contains("S3cr3t"));
            Throwable cause = e.getCause();
            assertNotNull("для лога остаётся санитизированная копия причины", cause);
            assertEquals(RuntimeExecutionException.class.getName() + ": login Админ/*** refused", cause.getMessage());
            assertArrayEquals("стек оригинала сохранён", raw.getStackTrace(), cause.getStackTrace());
            assertNull("у копии нет цепочки причин оригинала", cause.getCause());
        }
    }

    @Test
    public void storeCredentials_keepsAdditionalParametersOfCurrentSettings() throws Exception {
        when(access.resolveSettings(ref))
            .thenReturn(new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "Старый", "old", "/UC123"));

        ops().storeCredentials(ref, "Админ", "secret");

        ArgumentCaptor<IInfobaseAccessSettings> saved = ArgumentCaptor.forClass(IInfobaseAccessSettings.class);
        verify(access).updateSettings(eq(ref), saved.capture());
        assertEquals(InfobaseAccess.INFOBASE, saved.getValue().access());
        assertEquals("Админ", saved.getValue().userName());
        assertEquals("secret", saved.getValue().password());
        assertEquals("/UC123", saved.getValue().additionalProperties());
    }

    @Test
    public void storeCredentials_undefinedSettings_savedWithoutAdditionalParameters() throws Exception {
        when(access.resolveSettings(ref)).thenReturn(IInfobaseAccessSettings.NOT_DEFINED);

        ops().storeCredentials(ref, "Админ", "secret");

        ArgumentCaptor<IInfobaseAccessSettings> saved = ArgumentCaptor.forClass(IInfobaseAccessSettings.class);
        verify(access).updateSettings(eq(ref), saved.capture());
        assertEquals("Админ", saved.getValue().userName());
        assertNull(saved.getValue().additionalProperties());
    }

    @Test
    public void storeCredentials_unreadableSettings_refusedWithoutSaving() throws Exception {
        when(access.resolveSettings(ref)).thenThrow(new CoreException(Status.error("хранилище недоступно")));
        try {
            ops().storeCredentials(ref, "Админ", "secret");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("не удалось прочитать текущие настройки доступа к "
                + "базе Demo — учётные данные не сохранены, чтобы не потерять дополнительные параметры"));
            assertTrue(e.getMessage(), e.getMessage().contains("хранилище недоступно"));
        }
        verify(access, never()).updateSettings(any(), any());
    }

    /**
     * Пока замок базы держит EDT (другой поток), отмена задания должна срабатывать: ожидание
     * замка — порциями с проверкой монитора, а не непрерываемый lock(). Замок держится не дольше
     * 2 с — чтобы старая реализация (lock()) не повесила прогон, а просто не прошла проверку.
     */
    @Test(timeout = 20_000)
    public void infobaseLockHeldElsewhere_cancelledMonitor_givesUpWithoutRunning() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                locked.countDown();
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "infobase-lock-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue("поток-держатель не взял замок", locked.await(5, TimeUnit.SECONDS));
        ThickClientOps ops = ops(Duration.ofMillis(50));
        IProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        long t0 = System.nanoTime();
        try {
            ops.restoreDt(project, ref, Paths.get("E:/dumps/demo.dt"), monitor);
            fail("expected OperationCanceledException");
        } catch (OperationCanceledException expected) {
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue("отказ — после первого интервала ожидания, а не после освобождения замка: " + waitedMs
                + " мс", waitedMs < 1500);
        } finally {
            release.countDown();
        }
        verifyZeroInteractions(launcher);
        assertFalse(lock.isHeldByCurrentThread());
        holder.join(5000);
    }

    @Test
    public void interruptedWhileWaitingForInfobaseLock_cancels_andKeepsInterruptFlag() throws Exception {
        ThickClientOps ops = ops();
        Thread.currentThread().interrupt();
        try {
            ops.backupDt(project, ref, Paths.get("E:/dumps/b.dt"), new NullProgressMonitor());
            fail("expected OperationCanceledException");
        } catch (OperationCanceledException expected) {
            assertTrue("флаг прерывания восстановлен", Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // не оставлять флаг следующим тестам
        }
        verifyZeroInteractions(launcher);
        assertFalse(lock.isLocked());
    }

    @Test
    public void listExtensions_returnsNames() throws Exception {
        ThickClientOps ops = ops();
        when(launcher.listConfigurationExtensions(eq(component), eq(ref), any())).thenReturn(List.of("A", "B"));

        assertEquals(List.of("A", "B"), ops.listExtensions(project, ref, new NullProgressMonitor()));
    }

    @Test
    public void noStoredCredentials_runsWithoutUser() throws Exception {
        when(access.resolveSettings(ref)).thenThrow(new CoreException(Status.error("нет настроек")));

        ops().backupDt(project, ref, Paths.get("E:/dumps/b.dt"), new NullProgressMonitor());

        ArgumentCaptor<RuntimeExecutionArguments> args = ArgumentCaptor.forClass(RuntimeExecutionArguments.class);
        verify(launcher).exportDtFromInfobase(any(), any(), args.capture(), any());
        assertNull(args.getValue().getUsername());
    }

    @Test
    public void storeCredentials_savesInfobaseAccess() throws Exception {
        ops().storeCredentials(ref, "Админ", "secret");

        verify(access).updateSettings(eq(ref), argThat(s -> "Админ".equals(s.userName())
            && "secret".equals(s.password()) && s.access() == InfobaseAccess.INFOBASE));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void defaultResolver_resolvesThickClientForProjectAndInfobase() throws Exception {
        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation installation = mock(RuntimeInstallation.class);
        when(im.resolveByProjectAndInfobase(RuntimeCli.DefaultExecutableResolver.RUNTIME_TYPE_ID, project, ref,
            InfobaseAccessType.UPDATE)).thenReturn(resolvable);
        when(resolvable.resolve(List.of(IRuntimeComponentTypes.THICK_CLIENT), AppArch.AUTO)).thenReturn(installation);
        IRuntimeComponentManager cm = mock(IRuntimeComponentManager.class);
        when(cm.resolveExecutor(ILaunchableRuntimeComponent.class, IThickClientLauncher.class, installation,
            IRuntimeComponentTypes.THICK_CLIENT)).thenReturn(new ComponentExecutorInfo(installation, component, launcher));

        ThickClientOps.Launcher l = new ThickClientOps.DefaultLauncherResolver(im, cm).resolve(project, ref);

        assertSame(component, l.component());
        assertSame(launcher, l.launcher());
    }

    @Test
    public void defaultResolver_noInstallation_namesInfobase() throws Exception {
        when(ref.getName()).thenReturn("Demo");
        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        when(im.resolveByProjectAndInfobase(any(), any(), any(), any())).thenThrow(new MatchingRuntimeNotFound("нет 8.3.27"));
        try {
            new ThickClientOps.DefaultLauncherResolver(im, mock(IRuntimeComponentManager.class)).resolve(project, ref);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Demo"));
            assertTrue(e.getMessage(), e.getMessage().contains("нет 8.3.27"));
        }
    }
}
