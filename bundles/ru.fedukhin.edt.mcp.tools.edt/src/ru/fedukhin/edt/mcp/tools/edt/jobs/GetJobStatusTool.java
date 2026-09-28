package ru.fedukhin.edt.mcp.tools.edt.jobs;

import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.jobs.McpJob;
import ru.fedukhin.edt.mcp.core.jobs.McpJobs;

/**
 * Статус и результат фонового задания. Длинные инструменты (загрузка .dt, обновление проекта
 * из базы) отвечают сразу, а итог отдают через этот инструмент.
 */
public class GetJobStatusTool implements IMcpTool {

    static final int DEFAULT_WAIT_SECONDS = 60;
    /** Ниже ~5-минутного обрыва вызова транспортом MCP. */
    static final int MAX_WAIT_SECONDS = 240;

    private final Supplier<McpJobs> jobs;

    @Inject
    public GetJobStatusTool() {
        this(McpJobs::get);
    }

    public GetJobStatusTool(Supplier<McpJobs> jobs) {
        this.jobs = jobs;
    }

    @Override public String name() { return "get_job_status"; }

    @Override public String description() {
        return "Status and result of a background job started by a long-running tool "
            + "(create_infobase_from_dt, restore_infobase_from_dt, update_extensions_from_cfe, "
            + "update_project_from_infobase). waitSeconds (0-240, default 60) blocks until the job "
            + "finishes or the wait expires; call again while status is RUNNING.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> jobId = new LinkedHashMap<>();
        jobId.put("type", "string");
        jobId.put("description", "Job id returned by the tool that started the job");
        Map<String, Object> wait = new LinkedHashMap<>();
        wait.put("type", "integer");
        wait.put("minimum", 0);
        wait.put("maximum", MAX_WAIT_SECONDS);
        wait.put("description", "How long to wait for the job to finish, seconds (default 60)");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("jobId", jobId);
        properties.put("waitSeconds", wait);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("jobId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        Object idArg = args == null ? null : args.get("jobId");
        if (!(idArg instanceof String id) || id.isBlank()) {
            throw new ToolException("missing or empty 'jobId' argument");
        }
        int wait = parseWait(args.get("waitSeconds"));
        McpJob job = jobs.get().find(id).orElseThrow(() -> new ToolException(
            "задание '" + id + "' не найдено. Задания живут в памяти этой инстанции 1C:EDT и "
                + "теряются при её перезапуске; текущие задания покажет list_jobs"));
        if (wait > 0) job.await(Duration.ofSeconds(wait));
        return job.toMap();
    }

    /**
     * Ответ проходит общий слой обезличивания. Тексты ошибок платформы и СУБД — {@code error},
     * {@code steps[].message}, сообщения во вложенном {@code result} ({@code projects[].message},
     * {@code extensions[].message}) — несут данные базы: загрузка {@code .dt} рабочей базы в PostgreSQL
     * обычно падает нарушением уникального индекса с {@code DETAIL: Key (...)=(<значения>)}. Ключ
     * базы не переопределяется ({@code null} — «база неизвестна»): обезличивается всегда, флаг
     * {@code containsRealPersonalData} не проверяется. {@code list_jobs} отдаёт только сводку
     * (id, инструмент, статус, время, текущий шаг) и через слой не идёт.
     */
    @Override public boolean returnsInfobaseData() { return true; }

    private static int parseWait(Object value) throws ToolException {
        if (value == null) return DEFAULT_WAIT_SECONDS;
        if (!(value instanceof Number n)) throw new ToolException("waitSeconds must be an integer");
        int v = n.intValue();
        if (v < 0 || v > MAX_WAIT_SECONDS) {
            throw new ToolException("waitSeconds must be in [0, " + MAX_WAIT_SECONDS + "], got " + v);
        }
        return v;
    }
}
