package ru.fedukhin.edt.mcp.tools.testrun.di;

import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.tools.testrun.internal.ToolsTestrunActivator;

public class ToolsTestrunExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

    /** Cached statically so the 4 testrun tools share one injector and the
     *  TestRunnerLauncher/TestRunnerInstaller singletons. */
    private static volatile Injector injector;

    /** Returns the cached injector, or null if {@link #getInjector()} was never called. */
    public static Injector peekInjector() { return injector; }

    @Override protected Bundle getBundle() {
        return FrameworkUtil.getBundle(ToolsTestrunExecutableExtensionFactory.class);
    }

    @Override protected Injector getInjector() {
        Injector local = injector;
        if (local == null) {
            synchronized (ToolsTestrunExecutableExtensionFactory.class) {
                local = injector;
                if (local == null) {
                    local = Guice.createInjector(new ToolsTestrunModule(ToolsTestrunActivator.getInstance()));
                    injector = local;
                }
            }
        }
        return local;
    }
}
