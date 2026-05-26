package ru.fedukhin.edt.mcp.tools.md.di;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExecutableExtension;
import org.eclipse.core.runtime.IExecutableExtensionFactory;
import org.osgi.framework.FrameworkUtil;
import ru.fedukhin.edt.mcp.tools.md.ToolsMdActivator;

/**
 * Executable extension factory с глобально кешируемым {@link Injector} —
 * без кеша Equinox создаёт новый injector на каждый extension-point lookup
 * и singleton'ы перестают быть singleton'ами (урок Stage 3b a07e660).
 */
public final class ToolsMdExecutableExtensionFactory
        implements IExecutableExtensionFactory, IExecutableExtension {

    private static volatile Injector injector;
    private String className;

    @Override
    public void setInitializationData(IConfigurationElement config, String propertyName, Object data) {
        this.className = (data instanceof String) ? (String) data : null;
    }

    @Override
    public Object create() throws CoreException {
        try {
            if (className == null) {
                throw new IllegalStateException("class name not set on extension factory");
            }
            Class<?> clazz = FrameworkUtil.getBundle(ToolsMdExecutableExtensionFactory.class)
                                          .loadClass(className);
            return injector().getInstance(clazz);
        } catch (ClassNotFoundException e) {
            throw new CoreException(new org.eclipse.core.runtime.Status(
                    org.eclipse.core.runtime.IStatus.ERROR,
                    ToolsMdActivator.PLUGIN_ID,
                    "failed to create extension: " + className, e));
        }
    }

    private static Injector injector() {
        Injector i = injector;
        if (i == null) {
            synchronized (ToolsMdExecutableExtensionFactory.class) {
                i = injector;
                if (i == null) {
                    i = Guice.createInjector(new ToolsMdModule(ToolsMdActivator.getInstance()));
                    injector = i;
                }
            }
        }
        return i;
    }
}
