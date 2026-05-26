package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * {@code create_data_composition_schema} — MVP-tool для Stage 8f.
 * Создаёт минимальный skeleton {@code .dcs} в существующем Report и регистрирует
 * его в Report .mdo как {@code <templates>} + {@code <mainDataCompositionSchema>}.
 *
 * <p>Args: {@code { project, reportFqn, templateName? }}
 * <ul>
 *   <li>{@code reportFqn} — fqn вида {@code "Report.X"}. Report должен уже существовать
 *       (создаётся через {@code create_md_object kind=Report}).</li>
 *   <li>{@code templateName} — имя шаблона; default {@code "ОсновнаяСхема"}.</li>
 * </ul>
 * <p>Result: {@code { reportFqn, templateName, dcsPath, mdoChanged, dcsCreated }}.
 *
 * <p>Skeleton .dcs содержит:
 * <ul>
 *   <li>{@code <dataSource>ИсточникДанных</dataSource>} (Local);</li>
 *   <li>один пустой {@code <dataSet xsi:type="DataSetObject">НаборДанных1</dataSet>};</li>
 *   <li>один {@code <settingsVariant>Основной</settingsVariant>} с auto-selection / auto-order.</li>
 * </ul>
 *
 * <p>Дальше юзер file-level или через EDT GUI редактирует — добавляет fields,
 * dataSetLink, parameters, totalField, группировки в settingsVariant. Tool НЕ
 * реализует full DCS editing (это десятки EMF/XML конструкций со специфичными
 * namespace'ами); MVP закрывает «начало работы» — создание валидного skeleton'а
 * к которому можно подцепить редактор формы и продолжать в GUI.
 *
 * <p>Идемпотентно по .mdo (template/main ссылка не дублируется); по .dcs —
 * НЕ перезаписывает существующий файл, возвращает {@code dcsCreated=false}.
 */
public final class CreateDataCompositionSchemaTool implements IMcpTool {

    /** Skeleton .dcs — минимум валидный для EDT/платформы. */
    public static final String DCS_SKELETON = """
            <?xml version="1.0" encoding="UTF-8"?>
            <DataCompositionSchema xmlns="http://v8.1c.ru/8.1/data-composition-system/schema" xmlns:dcscom="http://v8.1c.ru/8.1/data-composition-system/common" xmlns:dcscor="http://v8.1c.ru/8.1/data-composition-system/core" xmlns:dcsset="http://v8.1c.ru/8.1/data-composition-system/settings" xmlns:v8="http://v8.1c.ru/8.1/data/core" xmlns:v8ui="http://v8.1c.ru/8.1/data/ui" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            \t<dataSource>
            \t\t<name>ИсточникДанных</name>
            \t\t<dataSourceType>Local</dataSourceType>
            \t</dataSource>
            \t<dataSet xsi:type="DataSetObject">
            \t\t<name>НаборДанных1</name>
            \t\t<dataSource>ИсточникДанных</dataSource>
            \t\t<objectName>НаборДанных1</objectName>
            \t</dataSet>
            \t<settingsVariant>
            \t\t<dcsset:name>Основной</dcsset:name>
            \t\t<dcsset:presentation xsi:type="v8:LocalStringType">
            \t\t\t<v8:item>
            \t\t\t\t<v8:lang>ru</v8:lang>
            \t\t\t\t<v8:content>Основной</v8:content>
            \t\t\t</v8:item>
            \t\t</dcsset:presentation>
            \t\t<dcsset:settings xmlns:style="http://v8.1c.ru/8.1/data/ui/style" xmlns:sys="http://v8.1c.ru/8.1/data/ui/fonts/system" xmlns:web="http://v8.1c.ru/8.1/data/ui/colors/web" xmlns:win="http://v8.1c.ru/8.1/data/ui/colors/windows">
            \t\t\t<dcsset:item xsi:type="dcsset:StructureItemGroup">
            \t\t\t\t<dcsset:order>
            \t\t\t\t\t<dcsset:item xsi:type="dcsset:OrderItemAuto"/>
            \t\t\t\t</dcsset:order>
            \t\t\t\t<dcsset:selection>
            \t\t\t\t\t<dcsset:item xsi:type="dcsset:SelectedItemAuto"/>
            \t\t\t\t</dcsset:selection>
            \t\t\t\t<dcsset:itemsViewMode>Normal</dcsset:itemsViewMode>
            \t\t\t</dcsset:item>
            \t\t</dcsset:settings>
            \t</settingsVariant>
            </DataCompositionSchema>
            """;

    public static final String DEFAULT_TEMPLATE_NAME = "ОсновнаяСхема";

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdoFileEditor           mdoEditor;
    private final IBmModelManager         bmModelManager;

    @Inject
    public CreateDataCompositionSchemaTool(MdoFileEditor mdoEditor, IBmModelManager bmModelManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), mdoEditor, bmModelManager);
    }

    /** Test seam. */
    public CreateDataCompositionSchemaTool(Supplier<IWorkspaceRoot> rootSupplier, MdoFileEditor mdoEditor) {
        this(rootSupplier, mdoEditor, null);
    }

    public CreateDataCompositionSchemaTool(Supplier<IWorkspaceRoot> rootSupplier,
                                            MdoFileEditor mdoEditor, IBmModelManager bmModelManager) {
        this.rootSupplier   = rootSupplier;
        this.mdoEditor      = mdoEditor;
        this.bmModelManager = bmModelManager;
    }

    @Override public String name()        { return "create_data_composition_schema"; }
    @Override public String description() {
        return "Stage 8f MVP: create a minimal .dcs skeleton in an existing Report and register it in "
             + "Report .mdo (<templates> + <mainDataCompositionSchema>). Skeleton has one empty DataSetObject "
             + "and one settingsVariant 'Основной' with auto-selection; further editing (fields, dataSetLink, "
             + "parameters, groupings) is file-level or via EDT GUI. Idempotent.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",      str);
        props.put("reportFqn",    str);
        props.put("templateName", str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "reportFqn"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName  = requireString(args, "project");
        String reportFqn    = requireString(args, "reportFqn");
        String templateName = args.get("templateName") instanceof String s && !s.isEmpty()
                ? s : DEFAULT_TEMPLATE_NAME;

        if (!reportFqn.startsWith("Report.")) {
            throw new ToolException("reportFqn must be 'Report.<Name>', got: " + reportFqn);
        }
        String reportName = reportFqn.substring("Report.".length());
        if (reportName.isEmpty()) {
            throw new ToolException("report name is empty in fqn: " + reportFqn);
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String mdoRel = "src/Reports/" + reportName + "/" + reportName + ".mdo";
        IFile reportMdo = project.getFile(mdoRel);
        if (!reportMdo.exists()) {
            throw new ToolException("report .mdo not found at " + mdoRel
                    + " (create with create_md_object kind=Report first)");
        }

        // 1. Создать (если нет) папки Templates/<TemplateName>/ и сам .dcs файл.
        String dcsRel = "src/Reports/" + reportName + "/Templates/" + templateName + "/Template.dcs";
        IFile dcsFile = project.getFile(dcsRel);
        boolean dcsCreated = false;
        try {
            if (!dcsFile.exists()) {
                ensureParents(dcsFile);
                byte[] bytes = DCS_SKELETON.getBytes(StandardCharsets.UTF_8);
                dcsFile.create(new ByteArrayInputStream(bytes), true, new NullProgressMonitor());
                dcsCreated = true;
            }
        } catch (CoreException e) {
            throw new ToolException("failed to create .dcs at " + dcsRel + ": " + e.getMessage());
        }

        // 2. Регистрация в Report .mdo.
        boolean mdoChanged = mdoEditor.addReportDcsTemplate(reportMdo, reportName, templateName);

        if (bmModelManager != null) {
            try { bmModelManager.waitModelSynchronization(project); }
            catch (Throwable ignored) { /* best-effort */ }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportFqn",    reportFqn);
        result.put("templateName", templateName);
        result.put("dcsPath",      dcsRel);
        result.put("mdoPath",      mdoRel);
        result.put("dcsCreated",   dcsCreated);
        result.put("mdoChanged",   mdoChanged);
        return result;
    }

    private static void ensureParents(IFile file) throws CoreException {
        if (file.getParent() instanceof IFolder folder) {
            ensureFolder(folder);
        }
    }

    private static void ensureFolder(IFolder folder) throws CoreException {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) ensureFolder(parent);
        folder.create(/*force*/ false, /*local*/ true, new NullProgressMonitor());
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
