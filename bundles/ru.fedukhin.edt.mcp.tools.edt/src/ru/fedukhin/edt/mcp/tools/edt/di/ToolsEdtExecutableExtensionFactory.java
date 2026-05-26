package ru.fedukhin.edt.mcp.tools.edt.di;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.core.McpCorePlugin;

public class ToolsEdtExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

    @Override protected Bundle getBundle() {
        return FrameworkUtil.getBundle(ToolsEdtExecutableExtensionFactory.class);
    }

    @Override protected Injector getInjector() {
        return Guice.createInjector(new ToolsEdtModule(McpCorePlugin.getPlugin()));
    }
}
