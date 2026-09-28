package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SchemaBuilder;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ToolArgs;

public class ToolArgsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void optional_blankIsNull_andWrongTypeRejected() throws Exception {
        assertNull(ToolArgs.optional(Map.of("a", "  "), "a"));
        assertNull(ToolArgs.optional(Map.of(), "a"));
        assertEquals("x", ToolArgs.optional(Map.of("a", "x"), "a"));
        expectError(() -> ToolArgs.optional(Map.of("a", 5), "a"), "a");
        expectError(() -> ToolArgs.required(Map.of(), "project"), "project");
    }

    @Test
    public void flagAndInteger_defaultsAndBounds() throws Exception {
        assertTrue(ToolArgs.flag(Map.of(), "f", true));
        assertFalse(ToolArgs.flag(Map.of("f", false), "f", true));
        assertEquals(180, ToolArgs.integer(Map.of(), "t", 180, 1, 1440));
        assertEquals(5, ToolArgs.integer(Map.of("t", 5), "t", 180, 1, 1440));
        expectError(() -> ToolArgs.integer(Map.of("t", 0), "t", 180, 1, 1440), "[1, 1440]");
        expectError(() -> ToolArgs.flag(Map.of("f", "yes"), "f", true), "boolean");
    }

    @Test
    public void existingFile_checksPresenceAbsolutenessAndExtension() throws Exception {
        Path dt = tmp.newFile("base.dt").toPath();
        assertEquals(dt, ToolArgs.existingFile("dtFile", dt.toString(), "dt"));
        expectError(() -> ToolArgs.existingFile("dtFile", dt.toString(), "cfe"), ".cfe");
        expectError(() -> ToolArgs.existingFile("dtFile", tmp.getRoot().toPath().resolve("нет.dt").toString(), "dt"),
            "не найден");
        expectError(() -> ToolArgs.existingFile("dtFile", "relative.dt", "dt"), "абсолютный");
        assertTrue(Files.exists(dt));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void schemaBuilder_buildsObjectWithRequiredAndBounds() {
        Map<String, Object> schema = SchemaBuilder.object()
            .string("project", "Project name", true)
            .stringEnum("type", "Kind", List.of("FILE", "SERVER"), false)
            .bool("force", "Force")
            .integer("timeoutMinutes", "Limit", 1, 1440)
            .array("items", "Items", SchemaBuilder.object().string("file", "Path", true).build(), true)
            .build();

        assertEquals("object", schema.get("type"));
        assertEquals(List.of("project", "items"), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(List.of("FILE", "SERVER"), ((Map<String, Object>) props.get("type")).get("enum"));
        assertEquals(1440, ((Map<String, Object>) props.get("timeoutMinutes")).get("maximum"));
        assertEquals("array", ((Map<String, Object>) props.get("items")).get("type"));
    }

    interface Call {
        void run() throws ToolException;
    }

    private static void expectError(Call call, String fragment) {
        try {
            call.run();
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(fragment));
        }
    }
}
