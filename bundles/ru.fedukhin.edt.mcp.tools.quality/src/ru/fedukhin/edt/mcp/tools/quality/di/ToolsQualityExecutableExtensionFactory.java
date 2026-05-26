package ru.fedukhin.edt.mcp.tools.quality.di;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.core.McpCorePlugin;

/**
 * Cached statically so all quality tools share the same {@link
 * ru.fedukhin.edt.mcp.tools.quality.internal.CheckCatalog} singleton. Without static caching,
 * Eclipse's executable-extension factory creates a fresh injector per {@code <tool>}
 * declaration and {@code @Singleton} only scopes within one injector — a snapshot loaded by
 * one tool would be invisible to the others. (Stage 3b learned this the hard way — commit
 * {@code a07e660}.)
 */
public class ToolsQualityExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

    private static volatile Injector injector;

    @Override protected Bundle getBundle() {
        return FrameworkUtil.getBundle(ToolsQualityExecutableExtensionFactory.class);
    }

    @Override protected Injector getInjector() {
        Injector local = injector;
        if (local == null) {
            synchronized (ToolsQualityExecutableExtensionFactory.class) {
                local = injector;
                if (local == null) {
                    local = Guice.createInjector(new ToolsQualityModule(McpCorePlugin.getPlugin()));
                    injector = local;
                }
            }
        }
        return local;
    }
}
