package ru.fedukhin.edt.mcp.tools.client.di;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Singleton;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.client.ListRunningClientsTool;
import ru.fedukhin.edt.mcp.tools.client.RunClientTool;
import ru.fedukhin.edt.mcp.tools.client.StopClientTool;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientLauncher;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry;
import ru.fedukhin.edt.mcp.tools.client.internal.InfobaseLookup;

public class ToolsClientModule extends AbstractServiceAwareModule {
    public ToolsClientModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        bind(IInfobaseManager.class).toService();
        bind(IInfobaseAccessManager.class).toService();
        bind(IResolvableRuntimeInstallationManager.class).toService();
        bind(IRuntimeComponentManager.class).toService();
        bind(InfobaseLookup.class);
        bind(ClientProcessRegistry.class).in(Singleton.class);
        bind(ClientLauncher.class);
        bind(RunClientTool.class);
        bind(ListRunningClientsTool.class);
        bind(StopClientTool.class);
    }
}
