package ru.fedukhin.edt.mcp.tools.md.di;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Singleton;
import org.eclipse.core.runtime.Plugin;
import ru.fedukhin.edt.mcp.tools.md.AddAttributeTool;
import ru.fedukhin.edt.mcp.tools.md.AddDcsCalculatedFieldTool;
import ru.fedukhin.edt.mcp.tools.md.AddDcsDataSetLinkTool;
import ru.fedukhin.edt.mcp.tools.md.AddDcsDataSetQueryTool;
import ru.fedukhin.edt.mcp.tools.md.AddDcsFieldTool;
import ru.fedukhin.edt.mcp.tools.md.AddDcsParameterTool;
import ru.fedukhin.edt.mcp.tools.md.AddDcsSettingFilterTool;
import ru.fedukhin.edt.mcp.tools.md.AddDcsSettingGroupingTool;
import ru.fedukhin.edt.mcp.tools.md.AddDcsTotalFieldTool;
import ru.fedukhin.edt.mcp.tools.md.SetDcsQueryTextTool;
import ru.fedukhin.edt.mcp.tools.md.SetDcsSettingParameterValueTool;
import ru.fedukhin.edt.mcp.tools.md.AddExtensionMethodOverrideTool;
import ru.fedukhin.edt.mcp.tools.md.AddRegisterRecorderTool;
import ru.fedukhin.edt.mcp.tools.md.AddSubsystemContentTool;
import ru.fedukhin.edt.mcp.tools.md.SetConstantTypeTool;
import ru.fedukhin.edt.mcp.tools.md.AddTabularSectionAttributeTool;
import ru.fedukhin.edt.mcp.tools.md.AddTabularSectionTool;
import ru.fedukhin.edt.mcp.tools.md.BorrowFormPicturesTool;
import ru.fedukhin.edt.mcp.tools.md.BorrowFormTool;
import ru.fedukhin.edt.mcp.tools.md.BorrowMdObjectTool;
import ru.fedukhin.edt.mcp.tools.md.CreateMdObjectTool;
import ru.fedukhin.edt.mcp.tools.md.CreateExternalObjectTool;
import ru.fedukhin.edt.mcp.tools.md.AddMdTemplateTool;
import ru.fedukhin.edt.mcp.tools.md.GetMdObjectTool;
import ru.fedukhin.edt.mcp.tools.md.ListAttributesTool;
import ru.fedukhin.edt.mcp.tools.md.ListMdObjectsTool;
import ru.fedukhin.edt.mcp.tools.md.RenameAttributeTool;
import ru.fedukhin.edt.mcp.tools.md.RenameMdObjectTool;
import ru.fedukhin.edt.mcp.tools.md.SetMdPropertyTool;
import ru.fedukhin.edt.mcp.tools.md.internal.AttributeFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.DcsFileEditor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectBorrower;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;
import ru.fedukhin.edt.mcp.tools.md.internal.ModuleFileBootstrap;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringFormatter;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringParser;

/**
 * DI-модуль бандла tools.md.
 *
 * Plan 1 биндит публичные EDT-сервисы (без ITopObjectFqnGenerator — Spike 3 §9.3
 * amendment: production-код использует литеральный FQN "<kind>.<name>", сервис не нужен)
 * и три плановых singleton (Registry/Parser/Formatter).
 *
 * Plan 2 добавляет MdObjectFactory, MdObjectLocator, AttributeFactory, PropertyAccessor,
 * ModuleFileBootstrap и 8 Tool-классов — биндинги для них появятся в этом же файле в Plan 2.
 */
public final class ToolsMdModule extends AbstractServiceAwareModule {

    public ToolsMdModule(Plugin plugin) { super(plugin); }

    @Override protected void doConfigure() {
        // Public EDT services
        bind(IBmModelManager.class).toService();
        bind(IV8ProjectManager.class).toService();
        bind(IConfigurationProvider.class).toService();
        bind(IResourceLookup.class).toService();

        // Internal singletons (Plan 1)
        bind(MdObjectRegistry.class).in(Singleton.class);
        bind(TypeStringParser.class).in(Singleton.class);
        bind(TypeStringFormatter.class).in(Singleton.class);

        // Plan 2 bindings — incrementally activated as classes land
        bind(MdObjectLocator.class);
        bind(ModuleFileBootstrap.class);
        bind(BmPersistentExecutor.class).in(Singleton.class);
        bind(MdObjectFactory.class);
        bind(AttributeFactory.class);
        bind(PropertyAccessor.class);
        // Stage 8d
        bind(MdObjectBorrower.class).in(Singleton.class);
        // Stage 8e
        bind(MdoFileEditor.class).in(Singleton.class);
        // Stage 8f extension — DCS editor
        bind(DcsFileEditor.class).in(Singleton.class);

        // Plan 2 tool bindings
        bind(ListMdObjectsTool.class);
        bind(GetMdObjectTool.class);
        bind(CreateMdObjectTool.class);
        bind(RenameMdObjectTool.class);
        bind(SetMdPropertyTool.class);
        bind(ListAttributesTool.class);
        bind(AddAttributeTool.class);
        bind(RenameAttributeTool.class);
        // Stage 8d
        bind(BorrowMdObjectTool.class);
        // Stage 8d v2
        bind(BorrowFormTool.class);
        // Stage 8e
        bind(AddTabularSectionTool.class);
        bind(AddTabularSectionAttributeTool.class);
        // Stage 9c
        bind(AddExtensionMethodOverrideTool.class);
        // v1.10.2 Bug #1
        bind(AddRegisterRecorderTool.class);
        // v1.10.2 plan item 12 — command-interface binding
        bind(AddSubsystemContentTool.class);
        // v1.10.3 — Constant.valueType via DOM
        bind(SetConstantTypeTool.class);
        // v1.11.0 — Stage 10: auto-borrow CommonPicture refs from a Form.form
        bind(BorrowFormPicturesTool.class);
        // v1.11.0 — Stage 8f sub-tools (.dcs editing)
        bind(AddDcsDataSetQueryTool.class);
        bind(AddDcsFieldTool.class);
        bind(AddDcsParameterTool.class);
        // v1.12.0 — Stage 8f expansion (.dcs richer editing)
        bind(AddDcsCalculatedFieldTool.class);
        bind(AddDcsTotalFieldTool.class);
        bind(AddDcsDataSetLinkTool.class);
        bind(SetDcsQueryTextTool.class);
        // 2026-05-31 — Stage 8g: settingsVariant editing
        bind(AddDcsSettingGroupingTool.class);
        bind(AddDcsSettingFilterTool.class);
        bind(SetDcsSettingParameterValueTool.class);
        // 2026-06-16 — Phase B: external objects + .mxlx print-form templates
        bind(CreateExternalObjectTool.class);
        bind(AddMdTemplateTool.class);
    }
}
