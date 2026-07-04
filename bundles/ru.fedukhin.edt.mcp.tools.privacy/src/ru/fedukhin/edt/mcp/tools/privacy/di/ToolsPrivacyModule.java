package ru.fedukhin.edt.mcp.tools.privacy.di;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.core.privacy.AuditLog;
import ru.fedukhin.edt.mcp.core.privacy.CatalogStore;
import ru.fedukhin.edt.mcp.core.privacy.InfobaseFlagStore;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyState;
import ru.fedukhin.edt.mcp.tools.privacy.BuildPiiCatalogTool;
import ru.fedukhin.edt.mcp.tools.privacy.GetPiiCatalogTool;
import ru.fedukhin.edt.mcp.tools.privacy.GetPrivacyAuditTool;
import ru.fedukhin.edt.mcp.tools.privacy.SetInfobasePiiFlagTool;

public class ToolsPrivacyModule extends AbstractServiceAwareModule {
    public ToolsPrivacyModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        bind(IBmModelManager.class).toService();
        bind(IConfigurationProvider.class).toService();
        bind(BuildPiiCatalogTool.class);
        bind(CatalogStore.class);
        bind(InfobaseFlagStore.class).toInstance(PrivacyState.flags());
        bind(AuditLog.class).toInstance(PrivacyState.audit());
        bind(GetPiiCatalogTool.class);
        bind(SetInfobasePiiFlagTool.class);
        bind(GetPrivacyAuditTool.class);
    }
}
