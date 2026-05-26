package ru.fedukhin.edt.mcp.tests.registry;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Status;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.internal.registry.ExtensionPointToolLoader;

public class ExtensionPointToolLoaderTest {

    @Test
    public void load_instantiatesToolsFromExtensionElements() throws CoreException {
        IExtensionRegistry reg = mock(IExtensionRegistry.class);
        IConfigurationElement el = mock(IConfigurationElement.class);
        IMcpTool tool = mock(IMcpTool.class);
        when(reg.getConfigurationElementsFor("ru.fedukhin.edt.mcp.core.tool"))
            .thenReturn(new IConfigurationElement[]{el});
        when(el.createExecutableExtension("class")).thenReturn(tool);

        ExtensionPointToolLoader loader = new ExtensionPointToolLoader(reg);
        List<IMcpTool> tools = loader.load();

        assertEquals(1, tools.size());
        assertEquals(tool, tools.get(0));
    }

    @Test
    public void load_skipsElementWithBrokenFactoryAndLogs() throws CoreException {
        IExtensionRegistry reg = mock(IExtensionRegistry.class);
        IConfigurationElement bad = mock(IConfigurationElement.class);
        IConfigurationElement good = mock(IConfigurationElement.class);
        IMcpTool tool = mock(IMcpTool.class);
        when(reg.getConfigurationElementsFor("ru.fedukhin.edt.mcp.core.tool"))
            .thenReturn(new IConfigurationElement[]{bad, good});
        when(bad.createExecutableExtension("class")).thenThrow(new CoreException(Status.error("boom")));
        when(good.createExecutableExtension("class")).thenReturn(tool);

        ExtensionPointToolLoader loader = new ExtensionPointToolLoader(reg);
        List<IMcpTool> tools = loader.load();

        assertEquals(1, tools.size());
        assertEquals(tool, tools.get(0));
    }
}
