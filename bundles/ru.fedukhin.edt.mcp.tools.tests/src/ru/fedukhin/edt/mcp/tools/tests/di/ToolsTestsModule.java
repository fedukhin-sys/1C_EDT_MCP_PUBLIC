package ru.fedukhin.edt.mcp.tools.tests.di;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Singleton;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.ModuleFileBootstrap;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;
import ru.fedukhin.edt.mcp.tools.tests.AddTestMethodTool;
import ru.fedukhin.edt.mcp.tools.tests.CreateTestModuleTool;
import ru.fedukhin.edt.mcp.tools.tests.GetTestMethodsTool;
import ru.fedukhin.edt.mcp.tools.tests.ListTestModulesTool;
import ru.fedukhin.edt.mcp.tools.tests.internal.BslTestMethodAppender;
import ru.fedukhin.edt.mcp.tools.tests.internal.TestModuleHeuristic;

/**
 * DI-модуль бандла tools.tests.
 *
 * Биндим: публичные EDT-сервисы + внутренние singletons.
 * Tool-биндинги добавляются в Tasks 12-14.
 */
public final class ToolsTestsModule extends AbstractServiceAwareModule {

    public ToolsTestsModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        // Public EDT services
        bind(IBmModelManager.class).toService();
        bind(IConfigurationProvider.class).toService();
        bind(IV8ProjectManager.class).toService();

        // Internal singletons
        bind(TestModuleHeuristic.class).in(Singleton.class);
        bind(BslTestMethodAppender.class).in(Singleton.class);
        // XUnitTemplates is a static-only utility (private ctor) — must NOT be bound.
        // Guice tried to provision it on Singleton scope and the entire MCP server
        // failed to start in 1.0.0/1.0.1 ("No injectable constructor"). All call sites
        // use XUnitTemplates.<static>() directly; no inject site exists.

        // tools.md.internal bindings (reused by CreateTestModuleTool)
        bind(MdObjectRegistry.class).in(Singleton.class);
        bind(BmPersistentExecutor.class).in(Singleton.class);
        bind(MdObjectFactory.class);
        bind(MdObjectLocator.class);
        bind(PropertyAccessor.class);
        bind(ModuleFileBootstrap.class);

        // Tool bindings
        bind(ListTestModulesTool.class);
        bind(GetTestMethodsTool.class);
        bind(CreateTestModuleTool.class);
        bind(AddTestMethodTool.class);
    }
}
