package ru.fedukhin.edt.mcp.tools.quality.di;

import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.google.inject.Singleton;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.quality.CheckCatalogTool;
import ru.fedukhin.edt.mcp.tools.quality.CheckDescribeTool;
import ru.fedukhin.edt.mcp.tools.quality.CheckListMarkersTool;
import ru.fedukhin.edt.mcp.tools.quality.CheckRunTool;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckCatalog;

/**
 * Guice wiring for {@code tools.quality}. Mirrors {@code ToolsDebugModule}.
 *
 * <p>Wires the managed services the spikes confirmed — check repository,
 * scheduler, marker store, and the derived-data completion signal — plus
 * {@link CheckCatalog} and the tool bindings.
 */
public class ToolsQualityModule extends AbstractServiceAwareModule {

    public ToolsQualityModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        bind(ICheckRepository.class).toService();
        bind(ICheckScheduler.class).toService();
        bind(IMarkerManagerV2.class).toService();
        bind(IDerivedDataManagerProvider.class).toService();

        bind(CheckCatalog.class).in(Singleton.class);

        bind(CheckCatalogTool.class);
        bind(CheckDescribeTool.class);
        bind(CheckRunTool.class);
        bind(CheckListMarkersTool.class);
    }
}
