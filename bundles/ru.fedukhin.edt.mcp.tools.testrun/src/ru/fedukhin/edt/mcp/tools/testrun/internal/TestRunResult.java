package ru.fedukhin.edt.mcp.tools.testrun.internal;

import java.util.List;

/**
 * Итог прогона.
 *
 * @param warning что в прогоне пошло не так, хотя результат получен (например, клиент 1С
 *                не закрылся сам и завершён принудительно), или {@code null}
 */
public record TestRunResult(int passed, int failed, long durationMs, List<TestResult> tests, String warning) {

    public TestRunResult(int passed, int failed, long durationMs, List<TestResult> tests) {
        this(passed, failed, durationMs, tests, null);
    }

    public TestRunResult withWarning(String text) {
        return new TestRunResult(passed, failed, durationMs, tests, text);
    }
}
