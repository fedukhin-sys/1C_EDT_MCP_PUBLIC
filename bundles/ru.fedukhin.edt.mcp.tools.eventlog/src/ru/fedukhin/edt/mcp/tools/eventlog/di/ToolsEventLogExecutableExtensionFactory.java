package ru.fedukhin.edt.mcp.tools.eventlog.di;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.core.McpCorePlugin;

public class ToolsEventLogExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

    private static volatile Injector injector;

    @Override protected Bundle getBundle() {
        return FrameworkUtil.getBundle(ToolsEventLogExecutableExtensionFactory.class);
    }

    @Override protected Injector getInjector() {
        Injector local = injector;
        if (local == null) {
            synchronized (ToolsEventLogExecutableExtensionFactory.class) {
                local = injector;
                if (local == null) {
                    local = Guice.createInjector(new ToolsEventLogModule(McpCorePlugin.getPlugin()));
                    injector = local;
                }
            }
        }
        return local;
    }
}
