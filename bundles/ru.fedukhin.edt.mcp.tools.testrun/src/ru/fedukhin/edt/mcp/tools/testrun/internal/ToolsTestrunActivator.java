package ru.fedukhin.edt.mcp.tools.testrun.internal;

import com.google.inject.Injector;
import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;
import ru.fedukhin.edt.mcp.tools.testrun.di.ToolsTestrunExecutableExtensionFactory;

/**
 * OSGi-активатор бандла tools.testrun. Хранит singleton {@link Plugin} — нужен
 * AbstractServiceAwareModule для разрешения публичных сервисов. На stop()
 * выключает TestRunnerLauncher executor (паттерн Stage 7d).
 */
public final class ToolsTestrunActivator extends Plugin {

    public static final String PLUGIN_ID = "ru.fedukhin.edt.mcp.tools.testrun";

    private static volatile ToolsTestrunActivator instance;
    public static ToolsTestrunActivator getInstance() { return instance; }

    @Override
    public void start(BundleContext ctx) throws Exception {
        super.start(ctx);
        instance = this;
    }

    @Override
    public void stop(BundleContext ctx) throws Exception {
        try {
            Injector inj = ToolsTestrunExecutableExtensionFactory.peekInjector();
            if (inj != null) {
                inj.getInstance(TestRunnerLauncher.class).shutdown();
            }
        } catch (RuntimeException ignored) {
            // bundle teardown best-effort
        }
        instance = null;
        super.stop(ctx);
    }
}
