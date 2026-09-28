package ru.fedukhin.edt.mcp.tests.tools.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.internal.protocol.ToolSpecAdapter;
import ru.fedukhin.edt.mcp.core.jobs.JobLauncher;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.core.privacy.AuditLog;
import ru.fedukhin.edt.mcp.core.privacy.InfobaseFlagStore;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalog;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyRedactor;
import ru.fedukhin.edt.mcp.core.privacy.Pseudonymizer;
import ru.fedukhin.edt.mcp.tools.edt.jobs.GetJobStatusTool;

public class GetJobStatusToolTest {

    @Test
    public void finishedJob_returnsFullState() throws Exception {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
        McpJob job = jobs.start("restore_infobase_from_dt", "проба", null, ctx -> ctx.put("restored", true), null);

        Map<String, Object> out = new GetJobStatusTool(() -> jobs).call(Map.of("jobId", job.id(), "waitSeconds", 0));

        assertEquals(job.id(), out.get("jobId"));
        assertEquals("SUCCEEDED", out.get("status"));
        assertEquals(Map.of("restored", true), out.get("result"));
    }

    @Test
    public void runningJob_waitExpires_returnsRunning() throws Exception {
        // Запуск, который никогда не исполняет работу: задание навсегда RUNNING.
        McpJobs jobs = new McpJobs((title, work, notStarted) -> () -> { }, Clock.systemUTC());
        McpJob job = jobs.start("tool_x", "висит", null, ctx -> { }, null);

        Map<String, Object> out = new GetJobStatusTool(() -> jobs).call(Map.of("jobId", job.id(), "waitSeconds", 1));

        assertEquals("RUNNING", out.get("status"));
    }

    @Test
    public void unknownJob_explainsThatJobsLiveInMemory() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
        try {
            new GetJobStatusTool(() -> jobs).call(Map.of("jobId", "j00000000"));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("list_jobs"));
        }
    }

    @Test
    public void waitAboveLimit_rejected() {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
        try {
            new GetJobStatusTool(() -> jobs).call(Map.of("jobId", "j00000000", "waitSeconds", 241));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("240"));
        }
    }

    @Test
    public void schema_requiresJobId() {
        Map<String, Object> schema = new GetJobStatusTool(() -> null).inputSchema();
        assertEquals(List.of("jobId"), schema.get("required"));
    }

    /**
     * Загрузка .dt рабочей базы в PostgreSQL обычно падает нарушением уникального индекса с
     * «DETAIL: Key (...)=(значения)» — это данные базы. Текст ошибки задания, сообщения шагов и
     * сообщения во вложенном результате обязаны пройти общий слой обезличивания.
     */
    @Test
    public void errorTexts_passPrivacyLayer_andAreMasked() throws Exception {
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), Clock.systemUTC());
        String dbmsError = "ERROR: duplicate key value violates unique constraint \"_reference12_byfield\" "
            + "DETAIL: Key (_fld34, _fld35)=(ivanov@example.com, 7707083893) already exists.";
        McpJob job = jobs.start("restore_infobase_from_dt", "проба", null, ctx -> {
            ctx.put("projects", List.of(Map.of("project", "Demo", "status", "FAILED", "message", dbmsError)));
            ctx.step("restore-dt", () -> { throw new IllegalStateException(dbmsError); });
        }, null);
        GetJobStatusTool tool = new GetJobStatusTool(() -> jobs);
        Map<String, Object> args = Map.of("jobId", job.id(), "waitSeconds", 0);
        Map<String, Object> raw = tool.call(args);
        assertTrue("без слоя ответ несёт данные базы: " + raw, String.valueOf(raw).contains("ivanov@example.com"));
        ToolSpecAdapter adapter = new ToolSpecAdapter(new PrivacyRedactor(
            () -> PiiCatalog.builder().build(),
            new Pseudonymizer("k".getBytes(StandardCharsets.UTF_8)),
            new InfobaseFlagStore(new HashMap<>()),
            new AuditLog()));

        String masked = String.valueOf(adapter.applyPrivacy(tool, args, raw));

        assertFalse(masked, masked.contains("ivanov@example.com"));
        assertFalse(masked, masked.contains("7707083893"));
        assertTrue(masked, masked.contains("[скрыто:email]"));
        assertTrue(masked, masked.contains("[скрыто:инн]"));
        assertTrue(tool.returnsInfobaseData());
        assertNull("база задания неизвестна слою — обезличивается всегда", tool.privacyInfobaseKey(args));
    }
}
