package ru.fedukhin.edt.mcp.tools.form;

import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;

/**
 * OSGi-активатор бандла tools.form. Хранит singleton {@link Plugin} —
 * нужен AbstractServiceAwareModule для разрешения публичных сервисов.
 */
public final class ToolsFormActivator extends Plugin {

    public static final String PLUGIN_ID = "ru.fedukhin.edt.mcp.tools.form";

    private static volatile ToolsFormActivator instance;

    public static ToolsFormActivator getInstance() { return instance; }

    @Override public void start(BundleContext ctx) throws Exception { super.start(ctx); instance = this; }
    @Override public void stop(BundleContext ctx) throws Exception { instance = null; super.stop(ctx); }
}
