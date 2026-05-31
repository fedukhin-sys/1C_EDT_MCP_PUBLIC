package ru.fedukhin.edt.mcp.tools.eventlog.di;

import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.eventlog.GetEventLogPathTool;
import ru.fedukhin.edt.mcp.tools.eventlog.QueryEventLogTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;

public class ToolsEventLogModule extends AbstractServiceAwareModule {
    public ToolsEventLogModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        // Reuse the same EDT-backed singletons that the infobase bundle binds. The
        // event-log tools only need InfobaseRegistry for name-to-reference lookup,
        // but Guice needs the full dep graph to be satisfied for the registry to be
        // constructible — hence the runtime/component bindings below.
        bind(IInfobaseManager.class).toService();
        bind(IRuntimeRegistry.class).toService();
        bind(IResolvableRuntimeInstallationManager.class).toService();
        bind(IRuntimeComponentManager.class).toService();
        bind(RuntimeCli.class);
        bind(InfobaseRegistry.class);
        bind(GetEventLogPathTool.class);
        bind(QueryEventLogTool.class);
    }
}
