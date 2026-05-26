package ru.fedukhin.edt.mcp.tools.md;

import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;

/**
 * OSGi-активатор бандла tools.md. Хранит singleton {@link Plugin} —
 * нужен AbstractServiceAwareModule для разрешения публичных сервисов.
 */
public final class ToolsMdActivator extends Plugin {

    public static final String PLUGIN_ID = "ru.fedukhin.edt.mcp.tools.md";

    private static volatile ToolsMdActivator instance;

    public static ToolsMdActivator getInstance() { return instance; }

    @Override public void start(BundleContext ctx) throws Exception { super.start(ctx); instance = this; }
    @Override public void stop(BundleContext ctx) throws Exception { instance = null; super.stop(ctx); }
}
