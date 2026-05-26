package ru.fedukhin.edt.mcp.ui.commands;

import com.google.inject.Injector;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import ru.fedukhin.edt.mcp.core.McpCorePlugin;
import ru.fedukhin.edt.mcp.core.internal.http.McpHttpService;
import ru.fedukhin.edt.mcp.core.internal.state.ServerStateBus;
import ru.fedukhin.edt.mcp.core.state.ServerState;

public class StopServerHandler extends AbstractHandler {

    private static final ILog LOG = Platform.getLog(StopServerHandler.class);

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        LOG.log(Status.info("Stop MCP server: handler invoked"));
        try {
            McpCorePlugin plugin = McpCorePlugin.getPlugin();
            if (plugin == null) throw new IllegalStateException("McpCorePlugin not activated");
            Injector injector = plugin.getInjector();
            if (injector == null) throw new IllegalStateException("McpCorePlugin injector is null");
            McpHttpService svc = injector.getInstance(McpHttpService.class);
            ServerStateBus bus = injector.getInstance(ServerStateBus.class);
            svc.stop();
            bus.publish(ServerState.stopped());
            LOG.log(Status.info("MCP server stopped"));
        } catch (Throwable t) {
            LOG.log(Status.error("Failed to stop MCP server", t));
            throw new ExecutionException("Failed to stop MCP server: " + t.getMessage(), t);
        }
        return null;
    }
}
