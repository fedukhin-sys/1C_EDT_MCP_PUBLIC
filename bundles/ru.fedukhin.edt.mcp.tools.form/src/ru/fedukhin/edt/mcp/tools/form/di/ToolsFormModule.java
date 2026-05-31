package ru.fedukhin.edt.mcp.tools.form.di;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Singleton;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.form.AddFormAttributeTool;
import ru.fedukhin.edt.mcp.tools.form.AddFormButtonTool;
import ru.fedukhin.edt.mcp.tools.form.AddFormCommandTool;
import ru.fedukhin.edt.mcp.tools.form.AddFormFieldTool;
import ru.fedukhin.edt.mcp.tools.form.AddFormGroupTool;
import ru.fedukhin.edt.mcp.tools.form.AddFormTableTool;
import ru.fedukhin.edt.mcp.tools.form.CreateFormTool;
import ru.fedukhin.edt.mcp.tools.form.GetFormItemTool;
import ru.fedukhin.edt.mcp.tools.form.GetFormTool;
import ru.fedukhin.edt.mcp.tools.form.ListFormsTool;
import ru.fedukhin.edt.mcp.tools.form.SetFormHandlerTool;
import ru.fedukhin.edt.mcp.tools.form.internal.FormFileEditor;
import ru.fedukhin.edt.mcp.tools.form.internal.FormFileReader;
import ru.fedukhin.edt.mcp.tools.form.internal.FormReader;
import ru.fedukhin.edt.mcp.tools.form.internal.FormRegistry;
import ru.fedukhin.edt.mcp.tools.form.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;

/**
 * DI-модуль бандла tools.form.
 *
 * Биндинги для инструментов добавляются по задачам 15-17.
 */
public final class ToolsFormModule extends AbstractServiceAwareModule {

    public ToolsFormModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        // Public EDT services
        bind(IBmModelManager.class).toService();
        bind(IConfigurationProvider.class).toService();
        bind(IV8ProjectManager.class).toService();

        // Internal singletons
        bind(FormRegistry.class).in(Singleton.class);
        bind(FormReader.class).in(Singleton.class);
        // 2026-05-31 A8 — disk-read для get_form
        bind(FormFileReader.class).in(Singleton.class);
        bind(FormFileEditor.class).in(Singleton.class);
        // BmPersistentExecutor from tools.md.internal — re-bind per-bundle.
        bind(BmPersistentExecutor.class).in(Singleton.class);

        // Stateless per-call
        bind(MdObjectLocator.class);

        // Tool bindings
        bind(ListFormsTool.class);
        bind(GetFormTool.class);
        bind(GetFormItemTool.class);
        bind(CreateFormTool.class);
        bind(AddFormAttributeTool.class);
        bind(AddFormCommandTool.class);
        // Stage 8c UI items
        bind(AddFormFieldTool.class);
        bind(AddFormGroupTool.class);
        bind(AddFormButtonTool.class);
        bind(AddFormTableTool.class);
        bind(SetFormHandlerTool.class);
    }
}
