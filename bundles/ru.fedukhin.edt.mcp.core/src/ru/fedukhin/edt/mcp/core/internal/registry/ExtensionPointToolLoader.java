package ru.fedukhin.edt.mcp.core.internal.registry;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;

public class ExtensionPointToolLoader {

    static final String EXTENSION_POINT_ID = "ru.fedukhin.edt.mcp.core.tool";

    private static final ILog LOG = Platform.getLog(ExtensionPointToolLoader.class);

    private final IExtensionRegistry registry;

    public ExtensionPointToolLoader(IExtensionRegistry registry) {
        this.registry = registry;
    }

    public List<IMcpTool> load() {
        List<IMcpTool> result = new ArrayList<>();
        IConfigurationElement[] elements = registry.getConfigurationElementsFor(EXTENSION_POINT_ID);
        for (IConfigurationElement el : elements) {
            try {
                Object instance = el.createExecutableExtension("class");
                if (instance instanceof IMcpTool) {
                    result.add((IMcpTool) instance);
                } else {
                    LOG.log(Status.warning("Extension class is not IMcpTool: " + instance.getClass()));
                }
            } catch (CoreException e) {
                LOG.log(Status.error("Failed to instantiate MCP tool from extension", e));
            }
        }
        return result;
    }
}
