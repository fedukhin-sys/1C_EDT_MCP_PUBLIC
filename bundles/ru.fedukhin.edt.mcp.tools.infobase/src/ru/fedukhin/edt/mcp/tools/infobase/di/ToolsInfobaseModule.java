package ru.fedukhin.edt.mcp.tools.infobase.di;

import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Singleton;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.infobase.AssociateInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.CreateInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.DeployProjectTool;
import ru.fedukhin.edt.mcp.tools.infobase.GetInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.ListInfobasesTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseDeployer;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;

public class ToolsInfobaseModule extends AbstractServiceAwareModule {
    public ToolsInfobaseModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        bind(IInfobaseManager.class).toService();
        bind(IInfobaseAssociationManager.class).toService();
        bind(IInfobaseSynchronizationManager.class).toService();
        bind(IRuntimeRegistry.class).toService();
        bind(IResolvableRuntimeInstallationManager.class).toService();
        bind(IRuntimeComponentManager.class).toService();
        bind(RuntimeCli.class);
        bind(InfobaseRegistry.class);
        bind(InfobaseDeployer.class).in(Singleton.class);
        bind(ListInfobasesTool.class);
        bind(GetInfobaseTool.class);
        bind(CreateInfobaseTool.class);
        bind(AssociateInfobaseTool.class);
        bind(DeployProjectTool.class);
    }
}
