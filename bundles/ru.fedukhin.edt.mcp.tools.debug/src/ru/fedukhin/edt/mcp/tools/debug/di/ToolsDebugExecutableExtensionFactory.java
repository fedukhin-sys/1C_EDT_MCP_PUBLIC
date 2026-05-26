package ru.fedukhin.edt.mcp.tools.debug.di;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.core.McpCorePlugin;

/**
 * Cached statically so all nine debug tools share the SAME
 * {@link ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry} singleton.
 * Without this, Eclipse's executable extension factory creates a fresh injector per
 * {@code <tool>} declaration and the {@code @Singleton} only scopes within one injector —
 * a session registered through one tool would be invisible to the others. (Stage 3b
 * commit {@code a07e660} learned this the hard way.)
 */
public class ToolsDebugExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

    private static volatile Injector injector;

    @Override protected Bundle getBundle() {
        return FrameworkUtil.getBundle(ToolsDebugExecutableExtensionFactory.class);
    }

    @Override protected Injector getInjector() {
        Injector local = injector;
        if (local == null) {
            synchronized (ToolsDebugExecutableExtensionFactory.class) {
                local = injector;
                if (local == null) {
                    local = Guice.createInjector(new ToolsDebugModule(McpCorePlugin.getPlugin()));
                    injector = local;
                }
            }
        }
        return local;
    }
}
