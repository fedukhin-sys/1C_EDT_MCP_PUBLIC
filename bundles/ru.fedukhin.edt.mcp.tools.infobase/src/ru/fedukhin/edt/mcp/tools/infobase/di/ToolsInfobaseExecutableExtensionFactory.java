package ru.fedukhin.edt.mcp.tools.infobase.di;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.core.McpCorePlugin;

public class ToolsInfobaseExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

    /**
     * Cached statically so all infobase tools share the SAME
     * {@link ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseDeployer} singleton
     * (which owns the deploy executor). Without this, Eclipse's executable
     * extension factory creates a fresh injector per {@code <tool>} declaration
     * and {@code @Singleton} only scopes within one injector — the executor
     * would be created per-tool, defeating its lifecycle.
     */
    private static volatile Injector injector;

    /** Returns the cached injector, or {@code null} if {@link #getInjector()} was never called. */
    public static Injector peekInjector() {
        return injector;
    }

    @Override protected Bundle getBundle() {
        return FrameworkUtil.getBundle(ToolsInfobaseExecutableExtensionFactory.class);
    }

    @Override protected Injector getInjector() {
        Injector local = injector;
        if (local == null) {
            synchronized (ToolsInfobaseExecutableExtensionFactory.class) {
                local = injector;
                if (local == null) {
                    local = Guice.createInjector(new ToolsInfobaseModule(McpCorePlugin.getPlugin()));
                    injector = local;
                }
            }
        }
        return local;
    }
}
