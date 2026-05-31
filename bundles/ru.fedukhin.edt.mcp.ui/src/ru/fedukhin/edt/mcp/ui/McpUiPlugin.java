package ru.fedukhin.edt.mcp.ui;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class McpUiPlugin extends AbstractUIPlugin {

    public static final String ID = "ru.fedukhin.edt.mcp.ui";
    private static McpUiPlugin instance;

    public static McpUiPlugin getPlugin() { return instance; }

    @Override public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        Platform.getLog(context.getBundle()).log(
                new Status(IStatus.INFO, ID, "McpUiPlugin.start() — instance bound"));
    }

    @Override public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }
}
