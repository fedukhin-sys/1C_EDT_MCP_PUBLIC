package ru.fedukhin.edt.mcp.core.jobs;

/** Тело шага задания. */
@FunctionalInterface
public interface StepAction<T> {
    T run() throws Exception;
}
