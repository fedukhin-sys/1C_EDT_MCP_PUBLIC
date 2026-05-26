package ru.fedukhin.edt.mcp.core.internal.di;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;
import ru.fedukhin.edt.mcp.core.McpCorePlugin;

public class McpCoreExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {
    @Override protected Bundle getBundle() {
        return McpCorePlugin.getPlugin().getBundle();
    }
    @Override protected Injector getInjector() {
        return McpCorePlugin.getPlugin().getInjector();
    }
}
