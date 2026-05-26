package ru.fedukhin.edt.mcp.tools.debug.di;

import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTargetManager;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointFactory;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Singleton;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.client.internal.ClientLauncher;
import ru.fedukhin.edt.mcp.tools.client.internal.InfobaseLookup;
import ru.fedukhin.edt.mcp.tools.debug.DebugClientTool;
import ru.fedukhin.edt.mcp.tools.debug.DebugPauseTool;
import ru.fedukhin.edt.mcp.tools.debug.DebugResumeTool;
import ru.fedukhin.edt.mcp.tools.debug.DebugStepTool;
import ru.fedukhin.edt.mcp.tools.debug.EvaluateTool;
import ru.fedukhin.edt.mcp.tools.debug.GetDebugStateTool;
import ru.fedukhin.edt.mcp.tools.debug.GetStackTool;
import ru.fedukhin.edt.mcp.tools.debug.GetVariablesTool;
import ru.fedukhin.edt.mcp.tools.debug.ListBreakpointsTool;
import ru.fedukhin.edt.mcp.tools.debug.ListDebugSessionsTool;
import ru.fedukhin.edt.mcp.tools.debug.RemoveBreakpointTool;
import ru.fedukhin.edt.mcp.tools.debug.SetBreakpointTool;
import ru.fedukhin.edt.mcp.tools.debug.SetVariableTool;
import ru.fedukhin.edt.mcp.tools.debug.StopDebugTool;
import ru.fedukhin.edt.mcp.tools.debug.internal.BreakpointService;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugEventSource;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugLauncher;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugPluginEventSource;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugSessionRegistry;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateReader;
import ru.fedukhin.edt.mcp.tools.debug.internal.EvaluationService;
import ru.fedukhin.edt.mcp.tools.debug.internal.ExceptionBreakpointService;

/**
 * Guice wiring for {@code tools.debug}. Mirrors {@code ToolsClientModule}.
 *
 * <p>{@code IBslBreakpointFactory} is bound via {@code .toService()} — confirmed by the
 * Plan 2 Task 1 spike (see {@code 2026-05-14-spike-stage-3c-debug.md}).
 */
public class ToolsDebugModule extends AbstractServiceAwareModule {

    public ToolsDebugModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        // EDT / Eclipse services. BreakpointService's @Inject constructor takes only
        // (IBslBreakpointFactory, IBreakpointManager) and hardcodes the workspace-root
        // supplier itself — no Supplier<IWorkspaceRoot> binding needed here.
        bind(IBslBreakpointFactory.class).toService();
        bind(IBreakpointManager.class).toInstance(DebugPlugin.getDefault().getBreakpointManager());

        // Plan 3 EDT services (for DebugLauncher + reused tools.client seams)
        bind(IRuntimeDebugClientTargetManager.class).toService();
        bind(IResolvableRuntimeInstallationManager.class).toService();
        bind(IRuntimeComponentManager.class).toService();
        bind(IInfobaseManager.class).toService();
        bind(IInfobaseAccessManager.class).toService();

        // Plan 1 foundation seams
        bind(DebugEventSource.class).to(DebugPluginEventSource.class);
        bind(DebugStateReader.class);
        bind(DebugSessionRegistry.class).in(Singleton.class);

        // Plan 2 seam
        bind(BreakpointService.class);
        bind(ExceptionBreakpointService.class);

        // Plan 3 seams
        bind(InfobaseLookup.class);
        bind(ClientLauncher.class);
        bind(DebugLauncher.class);
        bind(EvaluationService.class);

        // The 9 Plan-2 tools
        bind(SetBreakpointTool.class);
        bind(ListBreakpointsTool.class);
        bind(RemoveBreakpointTool.class);
        bind(GetStackTool.class);
        bind(ListDebugSessionsTool.class);
        bind(GetDebugStateTool.class);
        bind(DebugResumeTool.class);
        bind(DebugPauseTool.class);
        bind(DebugStepTool.class);

        // The 4 Plan-3 tools
        bind(DebugClientTool.class);
        bind(StopDebugTool.class);
        bind(GetVariablesTool.class);
        bind(EvaluateTool.class);
        bind(SetVariableTool.class);
    }
}
