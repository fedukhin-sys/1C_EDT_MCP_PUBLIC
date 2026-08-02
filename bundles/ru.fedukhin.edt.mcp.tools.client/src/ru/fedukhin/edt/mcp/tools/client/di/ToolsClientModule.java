package ru.fedukhin.edt.mcp.tools.client.di;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchManager;
import ru.fedukhin.edt.mcp.tools.client.ListLaunchConfigurationsTool;
import ru.fedukhin.edt.mcp.tools.client.ListRunningClientsTool;
import ru.fedukhin.edt.mcp.tools.client.RunClientTool;
import ru.fedukhin.edt.mcp.tools.client.RunLaunchConfigurationTool;
import ru.fedukhin.edt.mcp.tools.client.StopClientTool;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientLauncher;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry;
import ru.fedukhin.edt.mcp.tools.client.internal.InfobaseLookup;
import ru.fedukhin.edt.mcp.tools.client.internal.LaunchConfigService;

public class ToolsClientModule extends AbstractServiceAwareModule {
    public ToolsClientModule(Plugin plugin) { super(plugin); }

    /** {@link DebugPlugin} — Eclipse-синглтон, не OSGi-сервис: байндим провайдером, не {@code toService()}. */
    private static final class LaunchManagerProvider implements Provider<ILaunchManager> {
        @Override public ILaunchManager get() {
            return DebugPlugin.getDefault().getLaunchManager();
        }
    }

    @Override protected void doConfigure() {
        bind(IInfobaseManager.class).toService();
        bind(IInfobaseAccessManager.class).toService();
        bind(IResolvableRuntimeInstallationManager.class).toService();
        bind(IRuntimeComponentManager.class).toService();
        bind(ILaunchManager.class).toProvider(LaunchManagerProvider.class);
        bind(InfobaseLookup.class);
        bind(ClientProcessRegistry.class).in(Singleton.class);
        bind(ClientLauncher.class);
        bind(LaunchConfigService.class);
        bind(RunClientTool.class);
        bind(ListRunningClientsTool.class);
        bind(StopClientTool.class);
        bind(ListLaunchConfigurationsTool.class);
        bind(RunLaunchConfigurationTool.class);
    }
}
