package ru.fedukhin.edt.mcp.tools.edt.jobs;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;

/** Фоновые задания этой инстанции EDT — на случай, если jobId потерялся. */
public class ListJobsTool implements IMcpTool {

    private final Supplier<McpJobs> jobs;

    @Inject
    public ListJobsTool() {
        this(McpJobs::get);
    }

    public ListJobsTool(Supplier<McpJobs> jobs) {
        this.jobs = jobs;
    }

    @Override public String name() { return "list_jobs"; }

    @Override public String description() {
        return "Background jobs of this 1C:EDT instance, newest first: id, tool, status, start/finish "
            + "time, current step. Use it when a jobId got lost; details via get_job_status.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        List<Map<String, Object>> rows = jobs.get().list().stream().map(McpJob::summary).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobs", rows);
        return out;
    }
}
