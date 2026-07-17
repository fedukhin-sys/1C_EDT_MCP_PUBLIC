package ru.fedukhin.edt.mcp.core;

import com._1c.g5.wiring.InjectorAwareServiceRegistrator;
import com._1c.g5.wiring.ServiceInitialization;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.framework.BundleContext;
import ru.fedukhin.edt.mcp.core.internal.di.McpHttpModule;
import ru.fedukhin.edt.mcp.core.internal.di.McpRuntimeModule;
import ru.fedukhin.edt.mcp.core.internal.http.McpHttpService;
import ru.fedukhin.edt.mcp.core.internal.preferences.McpPreferences;
import ru.fedukhin.edt.mcp.core.internal.state.ServerStateBus;
import ru.fedukhin.edt.mcp.core.state.IServerStateBus;
import ru.fedukhin.edt.mcp.core.state.ServerState;

public class McpCorePlugin extends Plugin {

    public static final String ID = "ru.fedukhin.edt.mcp.core";

    private static McpCorePlugin instance;

    private Injector injector;
    private InjectorAwareServiceRegistrator services;
    private IPreferenceChangeListener portListener;

    public static McpCorePlugin getPlugin() { return instance; }
    public Injector getInjector() { return injector; }

    @Override public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        ILog log = Platform.getLog(getClass());
        log.log(Status.info("McpCorePlugin.start() — building Guice injector"));
        try {
            injector = Guice.createInjector(new McpRuntimeModule(), new McpHttpModule());
            log.log(Status.info("McpCorePlugin.start() — Guice injector built"));
            services = new InjectorAwareServiceRegistrator(context, injector);
            services.service(IServerStateBus.class).registerInjected();
            log.log(Status.info("McpCorePlugin.start() — IServerStateBus registered"));

            portListener = evt -> {
                if (McpPreferences.KEY_PORT.equals(evt.getKey())) {
                    restart();
                }
            };
            InstanceScope.INSTANCE.getNode(ID).addPreferenceChangeListener(portListener);

            ServiceInitialization.schedule(this::maybeAutoStart);
            log.log(Status.info("McpCorePlugin.start() — scheduled maybeAutoStart"));
        } catch (Throwable t) {
            log.log(Status.error("McpCorePlugin.start() — failed", t));
            // Don't swallow: re-throw so OSGi marks the bundle failed and the
            // user sees something in the Eclipse error log.
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
    }

    private void maybeAutoStart() {
        // Headless test runtime never auto-starts; the integration test drives
        // start/stop explicitly and we don't want a race with the listener above.
        if (Boolean.getBoolean("mcp.test.skipAutoStart")) return;
        ILog log = Platform.getLog(getClass());
        log.log(Status.info("McpCorePlugin.maybeAutoStart() — invoked"));
        McpPreferences prefs = injector.getInstance(McpPreferences.class);
        ServerStateBus bus = injector.getInstance(ServerStateBus.class);
        if (!prefs.isAutoStart()) {
            log.log(Status.info("Auto-start disabled in preferences — skipping"));
            return;
        }
        try {
            bus.publish(ServerState.starting(prefs.getPort()));
            injector.getInstance(McpHttpService.class).start();
            bus.publish(ServerState.running(prefs.getPort()));
            log.log(Status.info("MCP server auto-started on port " + prefs.getPort()));
        } catch (Throwable t) {
            log.log(Status.error("MCP server failed to auto-start", t));
            bus.publish(ServerState.error(t.getMessage()));
        }
    }

    private void restart() {
        ILog log = Platform.getLog(getClass());
        McpPreferences prefs = injector.getInstance(McpPreferences.class);
        ServerStateBus bus = injector.getInstance(ServerStateBus.class);
        try {
            injector.getInstance(McpHttpService.class).stop();
            bus.publish(ServerState.starting(prefs.getPort()));
            injector.getInstance(McpHttpService.class).start();
            bus.publish(ServerState.running(prefs.getPort()));
        } catch (Throwable t) {
            log.log(Status.error("MCP server failed to restart", t));
            bus.publish(ServerState.error(t.getMessage()));
        }
    }

    @Override public void stop(BundleContext context) throws Exception {
        try {
            // Слушатель переживает бандл: при reinstall осиротевший listener дёргает restart()
            // на мёртвом инжекторе.
            if (portListener != null) {
                InstanceScope.INSTANCE.getNode(ID).removePreferenceChangeListener(portListener);
                portListener = null;
            }
            if (injector != null) {
                injector.getInstance(McpHttpService.class).stop();
            }
            if (services != null) services.unregisterServices();
        } finally {
            instance = null;
            super.stop(context);
        }
    }
}
