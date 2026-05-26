package ru.fedukhin.edt.mcp.tests.tools.testrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestResultParser;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunResult;

public class TestResultParserTest {

    @Test public void valid_passedAndFailedMixed() throws Exception {
        String json = "{\"passed\":1,\"failed\":1,\"durationMs\":42,"
                + "\"tests\":["
                + "{\"module\":\"CommonModule.A\",\"name\":\"Тест_X\",\"status\":\"passed\",\"durationMs\":5},"
                + "{\"module\":\"CommonModule.A\",\"name\":\"Тест_Y\",\"status\":\"failed\",\"durationMs\":7,"
                + "\"message\":\"Ожидалось 5\"}"
                + "]}";
        TestRunResult r = TestResultParser.parse(json);
        assertEquals(1, r.passed());
        assertEquals(1, r.failed());
        assertEquals(42L, r.durationMs());
        assertEquals(2, r.tests().size());
        assertEquals("Тест_X", r.tests().get(0).name());
        assertEquals("passed", r.tests().get(0).status());
        assertNull("passed test has no message", r.tests().get(0).message());
        assertEquals("failed", r.tests().get(1).status());
        assertEquals("Ожидалось 5", r.tests().get(1).message());
    }

    @Test public void emptyTests_countsZero() throws Exception {
        String json = "{\"passed\":0,\"failed\":0,\"durationMs\":1,\"tests\":[]}";
        TestRunResult r = TestResultParser.parse(json);
        assertEquals(0, r.passed());
        assertEquals(0, r.failed());
        assertEquals(0, r.tests().size());
    }

    @Test public void malformedJson_throwsToolException() {
        try {
            TestResultParser.parse("not a json");
            fail("expected ToolException");
        } catch (ToolException e) {
            // ok
        }
    }

    @Test public void unicode_inMessage_preserved() throws Exception {
        String json = "{\"passed\":0,\"failed\":1,\"durationMs\":1,\"tests\":["
                + "{\"module\":\"М\",\"name\":\"Т\",\"status\":\"failed\",\"durationMs\":1,"
                + "\"message\":\"Ожидалось «5», получено «7»\"}]}";
        TestRunResult r = TestResultParser.parse(json);
        assertEquals("Ожидалось «5», получено «7»", r.tests().get(0).message());
    }

    @Test public void missingMessage_isNull() throws Exception {
        String json = "{\"passed\":1,\"failed\":0,\"durationMs\":1,\"tests\":["
                + "{\"module\":\"M\",\"name\":\"N\",\"status\":\"passed\",\"durationMs\":1}]}";
        TestRunResult r = TestResultParser.parse(json);
        assertNull(r.tests().get(0).message());
    }

    @Test public void explicitNullMessage_isNull() throws Exception {
        String json = "{\"passed\":1,\"failed\":0,\"durationMs\":1,\"tests\":["
                + "{\"module\":\"M\",\"name\":\"N\",\"status\":\"passed\",\"durationMs\":1,\"message\":null}]}";
        TestRunResult r = TestResultParser.parse(json);
        assertNull(r.tests().get(0).message());
    }
}
