package ru.fedukhin.edt.mcp.tools.testrun.internal;

import java.util.List;

public record TestRunResult(int passed, int failed, long durationMs, List<TestResult> tests) { }
