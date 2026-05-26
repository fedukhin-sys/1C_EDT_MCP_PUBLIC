package ru.fedukhin.edt.mcp.tools.client.di;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.core.McpCorePlugin;

public class ToolsClientExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

    /**
     * Cached statically so all three client tools (run/list/stop) share the SAME
     * {@link ru.fedukhin.edt.mcp.tools.client.internal.ClientProcessRegistry} singleton.
     * Without this, Eclipse's executable extension factory creates a fresh injector
     * per {@code <tool>} declaration and the {@code @Singleton} only scopes within
     * one injector — sessions registered by run_client would be invisible to stop_client.
     */
    private static volatile Injector injector;

    @Override protected Bundle getBundle() {
        return FrameworkUtil.getBundle(ToolsClientExecutableExtensionFactory.class);
    }

    @Override protected Injector getInjector() {
        Injector local = injector;
        if (local == null) {
            synchronized (ToolsClientExecutableExtensionFactory.class) {
                local = injector;
                if (local == null) {
                    local = Guice.createInjector(new ToolsClientModule(McpCorePlugin.getPlugin()));
                    injector = local;
                }
            }
        }
        return local;
    }
}
