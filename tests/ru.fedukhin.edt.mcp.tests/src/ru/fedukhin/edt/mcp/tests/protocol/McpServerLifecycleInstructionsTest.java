package ru.fedukhin.edt.mcp.tests.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.IToolRegistry;
import ru.fedukhin.edt.mcp.core.internal.protocol.McpServerLifecycle;
import ru.fedukhin.edt.mcp.core.internal.protocol.ToolSpecAdapter;
import ru.fedukhin.edt.mcp.core.internal.registry.ToolRegistry;

/**
 * The MCP initialize response carries an {@code instructions} string — a free-text
 * policy hint sent to every client on session start. EDT_MCP uses it to publish
 * the deploy-precheck rule so it survives bundle reinstall (unlike CLAUDE.md
 * or per-project memory).
 */
public class McpServerLifecycleInstructionsTest {

    @Test
    public void buildTransport_setsInstructionsWithDeployPrecheckPolicy() throws Exception {
        IToolRegistry registry = new ToolRegistry(Collections.emptyList());
        McpServerLifecycle lifecycle = new McpServerLifecycle(registry, new ToolSpecAdapter(null));
        lifecycle.buildTransport();

        // The server field is private — reflect into it like the other lifecycle test.
        Field serverField = McpServerLifecycle.class.getDeclaredField("server");
        serverField.setAccessible(true);
        McpSyncServer sync = (McpSyncServer) serverField.get(lifecycle);
        assertNotNull("buildTransport() must construct the McpSyncServer", sync);

        McpAsyncServer async = sync.getAsyncServer();
        Field instructionsField = McpAsyncServer.class.getDeclaredField("instructions");
        instructionsField.setAccessible(true);
        String instructions = (String) instructionsField.get(async);

        assertNotNull("instructions must be set on the MCP server", instructions);
        assertTrue("instructions must reference the check_list_markers precheck for deploy_project, "
                + "actual: " + instructions,
                instructions.contains("check_list_markers")
                        && instructions.contains("deploy_project"));
        assertTrue("instructions must spell out the BLOCKER/WARN triage so clients can act on it, "
                + "actual: " + instructions,
                instructions.contains("BLOCKER") && instructions.contains("WARN"));
    }

    /**
     * При нескольких инстанциях EDT на машине клиент подключается к одному из
     * нескольких портов. Инструкция обязана называть обслуживаемый workspace,
     * чтобы сессия могла обнаружить, что попала не туда, ещё до первого вызова.
     */
    @Test
    public void instructions_nameWorkspaceAndProjects() {
        McpServerLifecycle.WorkspaceIdentity id = new McpServerLifecycle.WorkspaceIdentity(
                "E:\\EDTProjects\\Demo", java.util.List.of("Demo", "Demo.Расширение"));

        String text = McpServerLifecycle.buildInstructions(id);

        assertTrue("инструкция должна называть путь workspace, actual: " + text,
                text.contains("E:\\EDTProjects\\Demo"));
        assertTrue("инструкция должна перечислять проекты, actual: " + text,
                text.contains("Demo.Расширение"));
        assertTrue("политика precheck должна сохраниться", text.contains("check_list_markers"));
        assertTrue("политика precheck должна сохраниться", text.contains("BLOCKER"));
    }

    /** Вне рабочей области (headless-тесты) политика обязана остаться, а заглушек быть не должно. */
    @Test
    public void instructions_survivesUnknownWorkspace() {
        String text = McpServerLifecycle.buildInstructions(null);

        assertTrue("без workspace политика всё равно на месте", text.contains("check_list_markers"));
        assertFalse("не должно быть заглушки null", text.contains("null"));
    }

    /** Заголовок сервера различает инстанции: в клиенте видно, с каким workspace идёт работа. */
    @Test
    public void serverTitle_carriesWorkspaceLeaf() throws Exception {
        IToolRegistry registry = new ToolRegistry(Collections.emptyList());
        McpServerLifecycle lifecycle = new McpServerLifecycle(registry, new ToolSpecAdapter(null),
                () -> new McpServerLifecycle.WorkspaceIdentity("E:\\EDTProjects\\Alpha",
                        java.util.List.of("AlphaBase")));
        lifecycle.buildTransport();

        Field serverField = McpServerLifecycle.class.getDeclaredField("server");
        serverField.setAccessible(true);
        McpSyncServer sync = (McpSyncServer) serverField.get(lifecycle);
        McpAsyncServer async = sync.getAsyncServer();

        Field infoField = McpAsyncServer.class.getDeclaredField("serverInfo");
        infoField.setAccessible(true);
        Object info = infoField.get(async);

        String title = (String) info.getClass().getMethod("title").invoke(info);
        assertTrue("title должен называть каталог workspace, actual: " + title,
                title != null && title.contains("Alpha"));
    }
}
