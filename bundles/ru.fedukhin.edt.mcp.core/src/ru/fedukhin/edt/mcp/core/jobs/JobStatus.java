package ru.fedukhin.edt.mcp.core.jobs;

/** Итог фонового задания MCP. {@link #RUNNING} — задание ещё выполняется. */
public enum JobStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
