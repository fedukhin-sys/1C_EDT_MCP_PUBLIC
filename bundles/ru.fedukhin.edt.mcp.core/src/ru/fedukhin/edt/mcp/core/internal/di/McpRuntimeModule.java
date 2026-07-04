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
import ru.fedukhin.edt.mcp.core.internal.security.SecureTokenStore;
import ru.fedukhin.edt.mcp.core.internal.state.ServerStateBus;
import ru.fedukhin.edt.mcp.core.privacy.AuditLog;
import ru.fedukhin.edt.mcp.core.privacy.CatalogStore;
import ru.fedukhin.edt.mcp.core.privacy.IPrivacyFilter;
import ru.fedukhin.edt.mcp.core.privacy.InfobaseFlagStore;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyRedactor;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyState;
import ru.fedukhin.edt.mcp.core.privacy.Pseudonymizer;
import ru.fedukhin.edt.mcp.core.state.IServerStateBus;

public class McpRuntimeModule extends AbstractModule {

    @Override protected void configure() {
        bind(IServerStateBus.class).to(ServerStateBus.class).in(Singleton.class);
        bind(ServerStateBus.class).in(Singleton.class);
        bind(McpServerLifecycle.class).in(Singleton.class);
        bind(CatalogStore.class).in(Singleton.class);
        bind(InfobaseFlagStore.class).toInstance(PrivacyState.flags());
        bind(AuditLog.class).toInstance(PrivacyState.audit());
    }

    @Provides @Singleton
    IToolRegistry toolRegistry() {
        List<IMcpTool> tools = new ExtensionPointToolLoader(Platform.getExtensionRegistry()).load();
        return new ToolRegistry(tools);
    }

    @Provides @Singleton
    Pseudonymizer pseudonymizer(SecureTokenStore tokens) {
        return new Pseudonymizer(tokens.getOrGeneratePrivacyKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Provides @Singleton
    IPrivacyFilter privacyFilter(CatalogStore catalog, Pseudonymizer pseudo,
                                 InfobaseFlagStore flags, AuditLog audit) {
        return new PrivacyRedactor(catalog::current, pseudo, flags, audit);
    }

    @Provides @Singleton
    ToolSpecAdapter toolSpecAdapter(IPrivacyFilter filter) {
        return new ToolSpecAdapter(filter);
    }
}
