package ru.fedukhin.edt.mcp.tools.bsl.di;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.core.McpCorePlugin;

public class ToolsBslExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

    @Override protected Bundle getBundle() {
        return FrameworkUtil.getBundle(ToolsBslExecutableExtensionFactory.class);
    }

    @Override protected Injector getInjector() {
        return Guice.createInjector(new ToolsBslModule(McpCorePlugin.getPlugin()));
    }
}
