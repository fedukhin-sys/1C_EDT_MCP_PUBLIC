package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com.google.inject.Injector;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import ru.fedukhin.edt.mcp.tools.infobase.di.ToolsInfobaseExecutableExtensionFactory;

/**
 * On bundle stop, shuts down the {@link InfobaseDeployer} executor that the
 * extension-factory's static-cached injector built. The deploy worker thread
 * is already a daemon — JVM exit alone is enough to kill it — but an explicit
 * shutdown lets us release the thread on bundle reinstall or update without
 * waiting for IDE-wide teardown.
 */
public class ToolsInfobaseActivator implements BundleActivator {

    @Override
    public void start(BundleContext context) {
        // No-op. Executor is created lazily on the first deployWithTimeout(...) call.
    }

    @Override
    public void stop(BundleContext context) {
        Injector injector = ToolsInfobaseExecutableExtensionFactory.peekInjector();
        if (injector == null) return; // no tool was ever instantiated → no executor exists
        try {
            injector.getInstance(InfobaseDeployer.class).shutdown();
        } catch (RuntimeException ignored) {
            // Bundle teardown is best-effort; ignore.
        }
    }
}
