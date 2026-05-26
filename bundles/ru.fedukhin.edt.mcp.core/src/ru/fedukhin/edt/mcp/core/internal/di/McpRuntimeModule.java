package ru.fedukhin.edt.mcp.core.internal.di;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import java.util.List;
import org.eclipse.core.runtime.Platform;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.IToolRegistry;
import ru.fedukhin.edt.mcp.core.internal.protocol.McpServerLifecycle;
import ru.fedukhin.edt.mcp.core.internal.protocol.ToolSpecAdapter;
import ru.fedukhin.edt.mcp.core.internal.registry.ExtensionPointToolLoader;
import ru.fedukhin.edt.mcp.core.internal.registry.ToolRegistry;
import ru.fedukhin.edt.mcp.core.internal.state.ServerStateBus;
import ru.fedukhin.edt.mcp.core.state.IServerStateBus;

public class McpRuntimeModule extends AbstractModule {

    @Override protected void configure() {
        bind(IServerStateBus.class).to(ServerStateBus.class).in(Singleton.class);
        bind(ServerStateBus.class).in(Singleton.class);
        bind(ToolSpecAdapter.class).in(Singleton.class);
        bind(McpServerLifecycle.class).in(Singleton.class);
    }

    @Provides @Singleton
    IToolRegistry toolRegistry() {
        List<IMcpTool> tools = new ExtensionPointToolLoader(Platform.getExtensionRegistry()).load();
        return new ToolRegistry(tools);
    }
}
