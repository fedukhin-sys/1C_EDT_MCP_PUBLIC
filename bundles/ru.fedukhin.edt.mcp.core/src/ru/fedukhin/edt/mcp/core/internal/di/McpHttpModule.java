package ru.fedukhin.edt.mcp.core.internal.di;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.eclipse.core.runtime.preferences.InstanceScope;
import ru.fedukhin.edt.mcp.core.internal.http.BearerAuthFilter;
import ru.fedukhin.edt.mcp.core.internal.http.McpHttpService;
import ru.fedukhin.edt.mcp.core.internal.preferences.McpPreferences;
import ru.fedukhin.edt.mcp.core.internal.protocol.McpServerLifecycle;
import ru.fedukhin.edt.mcp.core.internal.security.EquinoxSecureStringStore;
import ru.fedukhin.edt.mcp.core.internal.security.ISecureStringStore;
import ru.fedukhin.edt.mcp.core.internal.security.InMemoryStringStore;
import ru.fedukhin.edt.mcp.core.internal.security.SecureTokenStore;

public class McpHttpModule extends AbstractModule {

    @Override protected void configure() {}

    @Provides @Singleton
    McpPreferences preferences() {
        return new McpPreferences(InstanceScope.INSTANCE.getNode("ru.fedukhin.edt.mcp.core"));
    }

    @Provides @Singleton
    SecureTokenStore tokenStore() {
        ISecureStringStore backing = Boolean.getBoolean("mcp.security.useInMemory")
                ? new InMemoryStringStore()
                : new EquinoxSecureStringStore();
        return new SecureTokenStore(backing);
    }

    @Provides @Singleton
    BearerAuthFilter authFilter(SecureTokenStore tokens) {
        return new BearerAuthFilter(tokens::getOrGenerate);
    }

    @Provides @Singleton
    McpHttpService httpService(McpServerLifecycle lifecycle, BearerAuthFilter authFilter,
                               McpPreferences prefs) {
        return new McpHttpService(lifecycle, authFilter, prefs);
    }
}
