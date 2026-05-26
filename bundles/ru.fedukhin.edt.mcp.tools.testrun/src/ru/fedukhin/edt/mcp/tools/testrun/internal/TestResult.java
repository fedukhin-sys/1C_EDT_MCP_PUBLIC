package ru.fedukhin.edt.mcp.tools.testrun.internal;

public record TestResult(String module, String name, String status, long durationMs, String message) { }
