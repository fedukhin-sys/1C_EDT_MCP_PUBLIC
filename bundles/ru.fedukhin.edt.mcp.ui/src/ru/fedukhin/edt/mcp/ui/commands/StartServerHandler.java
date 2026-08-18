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
import ru.fedukhin.edt.mcp.core.internal.preferences.McpPreferences;
import ru.fedukhin.edt.mcp.core.internal.state.ServerStateBus;
import ru.fedukhin.edt.mcp.core.state.ServerState;

public class StartServerHandler extends AbstractHandler {

    private static final ILog LOG = Platform.getLog(StartServerHandler.class);

    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        LOG.log(Status.info("Start MCP server: handler invoked"));
        try {
            McpCorePlugin plugin = McpCorePlugin.getPlugin();
            if (plugin == null) {
                throw new IllegalStateException("McpCorePlugin not activated — its bundle failed to start");
            }
            Injector injector = plugin.getInjector();
            if (injector == null) {
                throw new IllegalStateException("McpCorePlugin injector is null — activator threw during start");
            }
            McpHttpService svc = injector.getInstance(McpHttpService.class);
            ServerStateBus bus = injector.getInstance(ServerStateBus.class);
            McpPreferences prefs = injector.getInstance(McpPreferences.class);
            bus.publish(ServerState.starting(prefs.getPort()));
            svc.start();
            // Фактически занятый порт, а не желаемый: при авто-подборе они расходятся.
            bus.publish(ServerState.running(svc.getPort()));
            LOG.log(Status.info("MCP server started on port " + svc.getPort()));
        } catch (Throwable t) {
            LOG.log(Status.error("Failed to start MCP server", t));
            throw new ExecutionException("Failed to start MCP server: " + t.getMessage(), t);
        }
        return null;
    }
}
