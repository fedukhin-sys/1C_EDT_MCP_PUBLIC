package ru.fedukhin.edt.mcp.tools.testrun.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ru.fedukhin.edt.mcp.core.api.ToolException;

public final class TestResultParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestResultParser() {}

    public static TestRunResult parse(String json) throws ToolException {
        // 1С Файл.Записать(..., КодировкаТекста.UTF8) добавляет UTF-8 BOM в начало,
        // Jackson его не принимает. Стрипаем перед парсингом.
        if (json != null && !json.isEmpty() && json.charAt(0) == '﻿') {
            json = json.substring(1);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ToolException("test result JSON parse failed: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new ToolException("test result JSON is not an object");
        }
        int passed = root.path("passed").asInt(0);
        int failed = root.path("failed").asInt(0);
        long duration = root.path("durationMs").asLong(0L);
        List<TestResult> tests = new ArrayList<>();
        JsonNode arr = root.path("tests");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                tests.add(new TestResult(
                    t.path("module").asText(""),
                    t.path("name").asText(""),
                    t.path("status").asText("unknown"),
                    t.path("durationMs").asLong(0L),
                    t.hasNonNull("message") ? t.get("message").asText() : null));
            }
        }
        return new TestRunResult(passed, failed, duration, Collections.unmodifiableList(tests));
    }
}
