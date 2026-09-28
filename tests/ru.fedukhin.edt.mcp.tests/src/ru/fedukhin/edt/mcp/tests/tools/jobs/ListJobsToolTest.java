package ru.fedukhin.edt.mcp.tests.tools.jobs;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.jobs.JobLauncher;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;
import ru.fedukhin.edt.mcp.tests.jobs.TestClock;
import ru.fedukhin.edt.mcp.tools.edt.jobs.ListJobsTool;

public class ListJobsToolTest {

    @Test
    @SuppressWarnings("unchecked")
    public void listsJobsNewestFirst() throws Exception {
        TestClock clock = new TestClock();
        McpJobs jobs = new McpJobs(JobLauncher.synchronous(), clock);
        McpJob older = jobs.start("tool_a", "старое", null, ctx -> { }, null);
        clock.advance(Duration.ofMinutes(1));
        McpJob newer = jobs.start("tool_b", "новое", null, ctx -> { }, null);

        Map<String, Object> out = new ListJobsTool(() -> jobs).call(Map.of());

        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("jobs");
        assertEquals(2, rows.size());
        assertEquals(newer.id(), rows.get(0).get("jobId"));
        assertEquals(older.id(), rows.get(1).get("jobId"));
        assertEquals("tool_b", rows.get(0).get("tool"));
        assertEquals("SUCCEEDED", rows.get(0).get("status"));
    }
}
