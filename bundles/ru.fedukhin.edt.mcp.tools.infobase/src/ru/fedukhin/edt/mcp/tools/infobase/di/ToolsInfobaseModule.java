package ru.fedukhin.edt.mcp.tools.infobase.di;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Singleton;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.infobase.AssociateInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.CreateInfobaseFromDtTool;
import ru.fedukhin.edt.mcp.tools.infobase.CreateInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.DeployProjectTool;
import ru.fedukhin.edt.mcp.tools.infobase.GetInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.ListInfobasesTool;
import ru.fedukhin.edt.mcp.tools.infobase.RestoreInfobaseFromDtTool;
import ru.fedukhin.edt.mcp.tools.infobase.UpdateExtensionsFromCfeTool;
import ru.fedukhin.edt.mcp.tools.infobase.UpdateProjectFromInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncStateJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseDeployer;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseJobs;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps;

public class ToolsInfobaseModule extends AbstractServiceAwareModule {
    public ToolsInfobaseModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        bind(IInfobaseManager.class).toService();
        bind(IInfobaseAssociationManager.class).toService();
        bind(IInfobaseSynchronizationManager.class).toService();
        bind(IRuntimeRegistry.class).toService();
        bind(IResolvableRuntimeInstallationManager.class).toService();
        bind(IRuntimeComponentManager.class).toService();
        bind(IV8ProjectManager.class).toService();
        bind(IInfobaseAccessManager.class).toService();
        bind(IBmModelManager.class).toService();
        // IResourceStoreManager тоже НЕ биндится: его службу на старых ветках никто не проверял, а без неё
        // упало бы создание ProjectFromInfobaseUpdater. Он берёт службу лениво (ServiceAccess).
        // IInfobaseSynchronizationStateManager сюда НЕ биндится: на EDT без API синхронизации v2
        // это уронило бы инжектор всех инструментов бандла. SyncV2 достаёт сервис лениво.
        bind(RuntimeCli.class);
        bind(InfobaseRegistry.class);
        bind(InfobaseDeployer.class).in(Singleton.class);
        bind(ListInfobasesTool.class);
        bind(GetInfobaseTool.class);
        bind(CreateInfobaseTool.class);
        bind(AssociateInfobaseTool.class);
        bind(DeployProjectTool.class);
        bind(ThickClientOps.class);
        bind(EdtSyncStateJobs.class);
        bind(SyncV2.class).in(Singleton.class);
        bind(ProjectFromInfobaseUpdater.class);
        bind(InfobaseTargets.class);
        bind(InfobaseJobs.class);
        bind(UpdateProjectFromInfobaseTool.class);
        bind(RestoreInfobaseFromDtTool.class);
        bind(UpdateExtensionsFromCfeTool.class);
        bind(CreateInfobaseFromDtTool.class);
    }
}
