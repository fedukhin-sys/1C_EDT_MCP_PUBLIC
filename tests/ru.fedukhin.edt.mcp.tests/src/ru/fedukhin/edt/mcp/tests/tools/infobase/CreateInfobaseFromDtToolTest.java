package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.version.Version;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.IProject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.jobs.JobLauncher;
import ru.fedukhin.edt.mcp.core.jobs.JobStatus;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyState;
import ru.fedukhin.edt.mcp.tools.infobase.CreateInfobaseFromDtTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ServerInfobaseParams;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps;

public class CreateInfobaseFromDtToolTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final InfobaseRegistry registry = mock(InfobaseRegistry.class);
    private final InfobaseTargets targets = mock(InfobaseTargets.class);
    private final SyncV2 syncV2 = mock(SyncV2.class);
    private final ProjectFromInfobaseUpdater updater = mock(ProjectFromInfobaseUpdater.class);
    private final ThickClientOps ops = mock(ThickClientOps.class);
    private final McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
    private final IProject project = mock(IProject.class);
    private final InfobaseReference created = mock(InfobaseReference.class);
    private final String ibName = "NewIB-" + UUID.randomUUID();
    private Path dt;

    private CreateInfobaseFromDtTool tool() throws Exception {
        dt = tmp.newFile("base.dt").toPath();
        when(project.getName()).thenReturn("Demo");
        when(created.getName()).thenReturn(ibName);
        when(targets.openProject("Demo")).thenReturn(project);
        IConfigurationProject configuration = mock(IConfigurationProject.class);
        when(configuration.getVersion()).thenReturn(new Version("8.3.27"));
        when(targets.configurationProject(project)).thenReturn(configuration);
        when(targets.associatedInfobases(project)).thenReturn(List.of());
        when(registry.findByName(anyString())).thenReturn(Optional.empty());
        when(registry.createFileInfobase(anyString(), any(UUID.class), any(Path.class), anyString(), isNull(), any()))
            .thenReturn(created);
        when(registry.createServerInfobase(anyString(), any(UUID.class), any(ServerInfobaseParams.class), anyString(),
            isNull(), any())).thenReturn(created);
        when(updater.update(any(), any(), anyBoolean(), any())).thenReturn(new ProjectFromInfobaseUpdater.Outcome("CHANGES_RESOLVED", 5L));
        when(ops.listExtensions(any(), any(), any())).thenReturn(List.of("Beta_Склад"));
        return new CreateInfobaseFromDtTool(registry, targets, syncV2, updater, ops,
            new InfobaseJobs(() -> jobs, Duration.ofMillis(300)));
    }

    private Map<String, Object> fileArgs(Object... extra) {
        Map<String, Object> args = new HashMap<>();
        args.put("project", "Demo");
        args.put("dtFile", dt.toString());
        args.put("type", "FILE");
        args.put("infobase", ibName);
        args.put("location", tmp.getRoot().toPath().resolve("ib").toString());
        for (int i = 0; i < extra.length; i += 2) args.put((String) extra[i], extra[i + 1]);
        return args;
    }

    private McpJob job(Map<String, Object> out) {
        return jobs.find((String) out.get("jobId")).orElseThrow();
    }

    @Test
    public void fileInfobase_createRestoreAssociateUpdate_inThatOrder() throws Exception {
        CreateInfobaseFromDtTool tool = tool();
        Path location = tmp.getRoot().toPath().resolve("ib");

        McpJob job = job(tool.call(fileArgs()));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        InOrder order = inOrder(registry, ops, targets, updater);
        order.verify(registry).createFileInfobase(eq(ibName), any(UUID.class), eq(location), eq("8.3.27"), isNull(), any());
        order.verify(ops).restoreDt(eq(project), eq(created), eq(dt), any());
        order.verify(targets).associate(project, created, true);
        // Сценарий 1 — новый проект заменяется целиком: флаг явно true (fix round 6, N3).
        order.verify(updater).update(eq(project), eq(created), eq(true), any());
        order.verify(ops).listExtensions(eq(project), eq(created), any());
        assertEquals(List.of("Beta_Склад"), job.result().get("extensionsInInfobase"));
        assertEquals("8.3.27", job.result().get("version"));
        assertEquals(true, job.result().get("piiFlagReset"));
        verify(ops, never()).storeCredentials(any(), any(), any());
    }

    /**
     * Флаг ПДн — сразу после создания базы и ДО загрузки .dt: прерванная загрузка не должна
     * оставить false от прежней базы с тем же именем (флаги при удалении базы не чистятся). UUID
     * базы выбирается заранее — флаг по нему известен ещё до загрузки.
     */
    @Test
    public void piiFlag_resetByNameAndUuid_afterCreation_beforeRestore_evenWhenRestoreFails() throws Exception {
        CreateInfobaseFromDtTool tool = tool();
        PrivacyState.flags().setFlag(ibName, false);
        AtomicReference<UUID> chosen = new AtomicReference<>();
        when(registry.createFileInfobase(anyString(), any(UUID.class), any(Path.class), anyString(), isNull(), any()))
            .thenAnswer(inv -> {
                UUID uuid = inv.getArgument(1);
                chosen.set(uuid);
                PrivacyState.flags().setFlag(uuid.toString(), false);
                return created;
            });
        AtomicReference<Boolean> byNameAtRestore = new AtomicReference<>();
        AtomicReference<Boolean> byUuidAtRestore = new AtomicReference<>();
        doAnswer(inv -> {
            byNameAtRestore.set(PrivacyState.flags().containsRealPersonalData(ibName));
            byUuidAtRestore.set(PrivacyState.flags().containsRealPersonalData(chosen.get().toString()));
            throw new ToolException("ошибка: загрузка .dt в базу — нет места на диске");
        }).when(ops).restoreDt(any(), any(), any(), any());

        McpJob job = job(tool.call(fileArgs()));

        assertEquals(JobStatus.FAILED, job.status());
        assertEquals("флаг по имени сброшен до загрузки .dt", Boolean.TRUE, byNameAtRestore.get());
        assertEquals("флаг по UUID сброшен до загрузки .dt", Boolean.TRUE, byUuidAtRestore.get());
        assertTrue(PrivacyState.flags().containsRealPersonalData(ibName));
        assertTrue(PrivacyState.flags().containsRealPersonalData(chosen.get().toString()));
        assertEquals(true, job.result().get("piiFlagReset"));
        verify(targets, never()).associate(any(), any(), anyBoolean());
    }

    /**
     * r5: CREATEINFOBASE не отменяется монитором, а отмена/лимит на нём оставили бы шаг pii-flag
     * невыполненным — и false прежней базы с тем же именем пережил бы задание. Флаг сбрасывается ещё
     * ДО создания базы (по имени и заранее выбранному UUID) и повторно — прямо перед загрузкой .dt.
     */
    @Test
    public void piiFlag_resetBeforeCreateInfobase_evenWhenCreationFails() throws Exception {
        CreateInfobaseFromDtTool tool = tool();
        PrivacyState.flags().setFlag(ibName, false);
        AtomicReference<Boolean> byNameAtCreate = new AtomicReference<>();
        when(registry.createFileInfobase(anyString(), any(UUID.class), any(Path.class), anyString(), isNull(), any()))
            .thenAnswer(inv -> {
                byNameAtCreate.set(PrivacyState.flags().containsRealPersonalData(ibName));
                throw new ToolException("1cv8 CREATEINFOBASE exited 1: нет места на диске");
            });

        McpJob job = job(tool.call(fileArgs()));

        assertEquals(JobStatus.FAILED, job.status());
        assertEquals("флаг по имени сброшен ещё до создания базы", Boolean.TRUE, byNameAtCreate.get());
        assertEquals(List.of(InfobaseJobs.EDT_CHECKS_STEP, "pii-flag", "create-infobase"),
            job.steps().stream().map(s -> s.name()).toList());
        assertEquals(true, job.result().get("piiFlagReset"));
        assertTrue(PrivacyState.flags().containsRealPersonalData(ibName));
    }

    @Test
    public void piiFlag_resetBeforeCreation_andAgainBeforeRestore() throws Exception {
        CreateInfobaseFromDtTool tool = tool();

        McpJob job = job(tool.call(fileArgs()));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        List<String> steps = job.steps().stream().map(s -> s.name()).toList();
        // Fix round 8 (L9): первый шаг — ожидание фоновых проверок EDT, до всего остального.
        assertEquals(steps.toString(), List.of(InfobaseJobs.EDT_CHECKS_STEP, "pii-flag", "create-infobase", "pii-flag",
            "restore-dt"), steps.subList(0, 5));
    }

    /** L4: защита вместо предупреждения — описание и схема говорят, что инструмент откажет. */
    @Test
    @SuppressWarnings("unchecked")
    public void serverDbName_protection_isDocumented() throws Exception {
        CreateInfobaseFromDtTool tool = tool();

        assertTrue(tool.description(), tool.description().contains("must name a NEW database — protected"));
        assertTrue(tool.description(), tool.description().contains("refuses before loading the .dt"));
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
        String dbName = String.valueOf(((Map<String, Object>) properties.get("dbName")).get("description"));
        assertTrue(dbName, dbName.contains("must be a NEW database — protected"));
        assertTrue(dbName, dbName.contains("refuses right after creation, before loading the .dt"));
    }

    @Test
    public void credentials_storedAfterRestore_andPasswordNeverEchoed() throws Exception {
        CreateInfobaseFromDtTool tool = tool();

        Map<String, Object> out = tool.call(fileArgs("user", "Админ", "password", "s3cr3t-pw"));
        McpJob job = job(out);

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        InOrder order = inOrder(ops, targets);
        order.verify(ops).restoreDt(eq(project), eq(created), eq(dt), any());
        order.verify(ops).storeCredentials(created, "Админ", "s3cr3t-pw");
        order.verify(targets).associate(project, created, true);
        assertFalse(String.valueOf(out), String.valueOf(out).contains("s3cr3t-pw"));
        assertFalse(String.valueOf(job.toMap()), String.valueOf(job.toMap()).contains("s3cr3t-pw"));
    }

    @Test
    public void serverInfobase_defaultsToLocalhostPostgresAndLockedJobs() throws Exception {
        CreateInfobaseFromDtTool tool = tool();
        Map<String, Object> args = fileArgs("type", "SERVER", "dbUser", "postgres", "dbPassword", "pw1",
            "clusterUser", "admin", "clusterPassword", "pw2");
        args.remove("location");

        McpJob job = job(tool.call(args));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        ArgumentCaptor<ServerInfobaseParams> params = ArgumentCaptor.forClass(ServerInfobaseParams.class);
        verify(registry).createServerInfobase(eq(ibName), any(UUID.class), params.capture(), eq("8.3.27"), isNull(), any());
        ServerInfobaseParams p = params.getValue();
        assertEquals("localhost", p.server());
        assertEquals(ibName, p.ref());
        assertEquals("PostgreSQL", p.dbms());
        assertEquals("localhost", p.dbServer());
        assertEquals(ibName, p.dbName());
        assertEquals("postgres", p.dbUser());
        assertEquals("pw1", p.dbPassword());
        assertEquals("admin", p.clusterUser());
        assertEquals("pw2", p.clusterPassword());
        assertTrue(p.createDatabase());
        assertTrue(p.lockScheduledJobs());
    }

    @Test
    public void existingInfobaseName_refusedWithHint() throws Exception {
        CreateInfobaseFromDtTool tool = tool();
        when(registry.findByName(ibName)).thenReturn(Optional.of(mock(InfobaseReference.class)));
        expectRefusal(tool, fileArgs(), "restore_infobase_from_dt");
    }

    @Test
    public void associatedProject_refusedUnlessDiscard() throws Exception {
        CreateInfobaseFromDtTool tool = tool();
        when(targets.associatedInfobases(project)).thenReturn(List.of("Старая"));
        expectRefusal(tool, fileArgs(), "discardProjectChanges");

        McpJob job = job(tool.call(fileArgs("discardProjectChanges", true)));
        assertEquals(JobStatus.SUCCEEDED, job.status());
    }

    @Test
    public void explicitVersion_overridesProjectVersion() throws Exception {
        CreateInfobaseFromDtTool tool = tool();

        tool.call(fileArgs("version", "8.3.27.2214"));

        verify(registry).createFileInfobase(eq(ibName), any(UUID.class), any(Path.class), eq("8.3.27.2214"), isNull(), any());
    }

    @Test
    public void nonEmptyLocation_refused() throws Exception {
        CreateInfobaseFromDtTool tool = tool();
        Path location = tmp.newFolder("busy").toPath();
        Files.createFile(location.resolve("1Cv8.1CD"));
        expectRefusal(tool, fileArgs("location", location.toString()), "не пуст");
    }

    @Test
    public void invalidServerValue_refusedBeforeJob() throws Exception {
        CreateInfobaseFromDtTool tool = tool();
        Map<String, Object> args = fileArgs("type", "SERVER", "dbUser", "postgres", "dbPassword", "с пробелом");
        args.remove("location");
        expectRefusal(tool, args, "dbPassword");
        assertTrue(jobs.list().isEmpty());
    }

    @Test
    public void creationFailure_failsJob_withoutRestore() throws Exception {
        CreateInfobaseFromDtTool tool = tool();
        when(registry.createFileInfobase(anyString(), any(UUID.class), any(Path.class), anyString(), isNull(), any()))
            .thenThrow(new ToolException("1cv8 CREATEINFOBASE exited 1: нет прав на каталог"));

        McpJob job = job(tool.call(fileArgs()));

        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.error(), job.error().contains("нет прав"));
        verify(ops, never()).restoreDt(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void projectStillDirtyAfterUpdate_reportsWarning() throws Exception {
        CreateInfobaseFromDtTool tool = tool();
        when(updater.update(any(), any(), anyBoolean(), any()))
            .thenReturn(new ProjectFromInfobaseUpdater.Outcome("CHANGES_RESOLVED", 5L, "правки проекта Demo остались"));

        McpJob job = job(tool.call(fileArgs()));

        assertEquals(job.error(), JobStatus.SUCCEEDED, job.status());
        Map<String, Object> projectUpdate = (Map<String, Object>) job.result().get("projectUpdate");
        assertEquals("CHANGES_RESOLVED", projectUpdate.get("resolution"));
        assertEquals("правки проекта Demo остались", projectUpdate.get("warning"));
    }

    private void expectRefusal(CreateInfobaseFromDtTool tool, Map<String, Object> args, String fragment) {
        try {
            tool.call(args);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(fragment));
        }
    }
}
