package ru.fedukhin.edt.mcp.tools.privacy.di;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.core.McpCorePlugin;

public class ToolsPrivacyExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

    private static volatile Injector injector;

    @Override protected Bundle getBundle() {
        return FrameworkUtil.getBundle(ToolsPrivacyExecutableExtensionFactory.class);
    }

    @Override protected Injector getInjector() {
        Injector local = injector;
        if (local == null) {
            synchronized (ToolsPrivacyExecutableExtensionFactory.class) {
                local = injector;
                if (local == null) {
                    local = Guice.createInjector(new ToolsPrivacyModule(McpCorePlugin.getPlugin()));
                    injector = local;
                }
            }
        }
        return local;
    }
}
