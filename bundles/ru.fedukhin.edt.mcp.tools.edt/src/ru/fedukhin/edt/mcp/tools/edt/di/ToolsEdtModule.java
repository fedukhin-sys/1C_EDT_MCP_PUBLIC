package ru.fedukhin.edt.mcp.tools.edt.di;

import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.edt.ListProjectsTool;
import ru.fedukhin.edt.mcp.tools.edt.workspace.CloseProjectTool;
import ru.fedukhin.edt.mcp.tools.edt.workspace.CreateProjectTool;
import ru.fedukhin.edt.mcp.tools.edt.workspace.GetProjectTool;
import ru.fedukhin.edt.mcp.tools.edt.workspace.GetWorkspaceInfoTool;
import ru.fedukhin.edt.mcp.tools.edt.workspace.ListProjectFilesTool;
import ru.fedukhin.edt.mcp.tools.edt.workspace.ListRuntimeVersionsTool;
import ru.fedukhin.edt.mcp.tools.edt.workspace.OpenProjectTool;

public class ToolsEdtModule extends AbstractServiceAwareModule {
    public ToolsEdtModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        bind(IConfigurationProjectManager.class).toService();
        bind(IConfigurationProvider.class).toService();
        bind(IExtensionProjectManager.class).toService();
        bind(IExternalObjectProjectManager.class).toService();
        bind(IV8ProjectManager.class).toService();
        bind(IRuntimeVersionSupport.class).toService();
        bind(IRuntimeRegistry.class).toService();

        bind(CloseProjectTool.class);
        bind(CreateProjectTool.class);
        bind(GetProjectTool.class);
        bind(GetWorkspaceInfoTool.class);
        bind(ListProjectFilesTool.class);
        bind(ListProjectsTool.class);
        bind(ListRuntimeVersionsTool.class);
        bind(OpenProjectTool.class);
    }
}
