package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import com.google.inject.Injector;
import com.google.inject.Guice;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.internal.di.McpRuntimeModule;
import ru.fedukhin.edt.mcp.core.internal.di.McpHttpModule;
import ru.fedukhin.edt.mcp.core.internal.protocol.ToolSpecAdapter;
import ru.fedukhin.edt.mcp.core.privacy.IPrivacyFilter;

/** Проверяет, что реальный Guice-граф резолвит привязки слоя обезличивания. */
public class PrivacyDiWiringTest {

    @Test public void injectorResolvesPrivacyBindings() {
        Injector injector = Guice.createInjector(new McpRuntimeModule(), new McpHttpModule());
        // не должно бросать ConfigurationException/ProvisionException
        assertNotNull(injector.getInstance(IPrivacyFilter.class));
        assertNotNull(injector.getInstance(ToolSpecAdapter.class));
    }
}
