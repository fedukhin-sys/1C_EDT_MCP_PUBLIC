package ru.fedukhin.edt.mcp.tools.bsl.di;

import com._1c.g5.wiring.AbstractServiceAwareModule;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.bsl.GetMethodTool;
import ru.fedukhin.edt.mcp.tools.bsl.GetModuleInfoTool;
import ru.fedukhin.edt.mcp.tools.bsl.ListModuleMethodsTool;
import ru.fedukhin.edt.mcp.tools.bsl.ReadModuleTool;
import ru.fedukhin.edt.mcp.tools.bsl.WriteModuleTool;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader;

public class ToolsBslModule extends AbstractServiceAwareModule {
    public ToolsBslModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        bind(BslAstReader.class);
        bind(GetMethodTool.class);
        bind(GetModuleInfoTool.class);
        bind(ListModuleMethodsTool.class);
        bind(ReadModuleTool.class);
        bind(WriteModuleTool.class);
    }
}
