package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import ru.fedukhin.edt.mcp.tools.md.internal.MxlxTemplateBuilder;
import ru.fedukhin.edt.mcp.tools.md.internal.MxlxTemplateBuilder.Cell;
import ru.fedukhin.edt.mcp.tools.md.internal.MxlxTemplateBuilder.Row;

/**
 * {@code add_md_template} — создаёт spreadsheet-макет печатной формы ({@code .mxlx})
 * по структурному спеку {@code columns}/{@code rows} и регистрирует его в {@code .mdo}
 * владельца как {@code <templates>}.
 *
 * <p>Владелец задаётся {@code ownerFqn = "<Kind>.<Name>"}, поддерживаются kind'ы,
 * у которых есть макеты: ExternalDataProcessor, ExternalReport, DataProcessor, Report,
 * Catalog, Document, InformationRegister, AccumulationRegister, BusinessProcess, Task.
 *
 * <p>Args: {@code { project, ownerFqn, templateName, synonym?, areaName?, columns?, rows?, overwrite? }}.
 * <ul>
 *   <li>{@code columns} — массив ширин колонок (число колонок = длина; по умолчанию [800]);</li>
 *   <li>{@code rows} — массив строк; строка = {@code { cells: [ cell… ] }};
 *       cell = {@code { text? | parameter?, span?, rowSpan?, bold?, size?, align?, valign?, wrap? }}.
 *       {@code align ∈ {left,center,right,justify}}, {@code valign ∈ {top,center,bottom}};
 *       {@code span>1}/{@code rowSpan>1} → объединение ячеек;</li>
 *   <li>{@code areaName} — имя именованной области (для {@code ПолучитьОбласть}); default = templateName;</li>
 *   <li>{@code overwrite} — перезаписать существующий Template.mxlx (default false — НЕ трогать).</li>
 * </ul>
 * <p>Result: {@code { ownerFqn, templateName, mxlxPath, mdoPath, mxlxCreated, mdoChanged }}.
 */
public final class AddMdTemplateTool implements IMcpTool {

    /** kind → папка в src/. */
    private static final Map<String, String> KIND_FOLDER = Map.ofEntries(
            Map.entry("ExternalDataProcessor", "ExternalDataProcessors"),
            Map.entry("ExternalReport",        "ExternalReports"),
            Map.entry("DataProcessor",         "DataProcessors"),
            Map.entry("Report",                "Reports"),
            Map.entry("Catalog",               "Catalogs"),
            Map.entry("Document",              "Documents"),
            Map.entry("InformationRegister",   "InformationRegisters"),
            Map.entry("AccumulationRegister",  "AccumulationRegisters"),
            Map.entry("BusinessProcess",       "BusinessProcesses"),
            Map.entry("Task",                  "Tasks"));

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdoFileEditor            mdoEditor;
    private final IBmModelManager          bmModelManager;
    private final MxlxTemplateBuilder      builder = new MxlxTemplateBuilder();

    @Inject
    public AddMdTemplateTool(MdoFileEditor mdoEditor, IBmModelManager bmModelManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), mdoEditor, bmModelManager);
    }

    /** Test seam. */
    public AddMdTemplateTool(Supplier<IWorkspaceRoot> rootSupplier, MdoFileEditor mdoEditor) {
        this(rootSupplier, mdoEditor, null);
    }

    public AddMdTemplateTool(Supplier<IWorkspaceRoot> rootSupplier, MdoFileEditor mdoEditor,
                             IBmModelManager bmModelManager) {
        this.rootSupplier   = rootSupplier;
        this.mdoEditor      = mdoEditor;
        this.bmModelManager = bmModelManager;
    }

    @Override public String name()        { return "add_md_template"; }
    @Override public String description() {
        return "Generate a spreadsheet print-form template (.mxlx) from a columns/rows spec and register "
             + "it in the owner's .mdo as <templates>. ownerFqn='<Kind>.<Name>' "
             + "(ExternalDataProcessor/ExternalReport/DataProcessor/Report/Catalog/Document/…). "
             + "rows[].cells[] support text|parameter, span/rowSpan (merge), bold, size, align, valign, wrap.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str  = Map.of("type", "string");
        Map<String, Object> bool = Map.of("type", "boolean");

        Map<String, Object> cellProps = new LinkedHashMap<>();
        cellProps.put("text",      str);
        cellProps.put("parameter", str);
        cellProps.put("span",      Map.of("type", "integer"));
        cellProps.put("rowSpan",   Map.of("type", "integer"));
        cellProps.put("bold",      bool);
        cellProps.put("size",      Map.of("type", "integer"));
        cellProps.put("align",     Map.of("type", "string", "enum", List.of("left", "center", "right", "justify")));
        cellProps.put("valign",    Map.of("type", "string", "enum", List.of("top", "center", "bottom")));
        cellProps.put("wrap",      bool);
        Map<String, Object> cellSchema = Map.of(
                "type", "object", "properties", cellProps, "additionalProperties", false);

        Map<String, Object> rowSchema = Map.of(
                "type", "object",
                "properties", Map.of("cells", Map.of("type", "array", "items", cellSchema)),
                "additionalProperties", false);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",      str);
        props.put("ownerFqn",     str);
        props.put("templateName", str);
        props.put("synonym",      str);
        props.put("areaName",     str);
        props.put("columns",      Map.of("type", "array", "items", Map.of("type", "integer")));
        props.put("rows",         Map.of("type", "array", "items", rowSchema));
        props.put("overwrite",    bool);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "ownerFqn", "templateName"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName  = requireString(args, "project");
        String ownerFqn     = requireString(args, "ownerFqn");
        String templateName = requireString(args, "templateName");
        String synonym      = args.get("synonym")  instanceof String s && !s.isEmpty() ? s : null;
        String areaName     = args.get("areaName") instanceof String s && !s.isEmpty() ? s : templateName;
        boolean overwrite   = Boolean.TRUE.equals(args.get("overwrite"));

        int dot = ownerFqn.indexOf('.');
        if (dot <= 0 || dot == ownerFqn.length() - 1) {
            throw new ToolException("ownerFqn must be '<Kind>.<Name>', got: " + ownerFqn);
        }
        String kind = ownerFqn.substring(0, dot);
        String owner = ownerFqn.substring(dot + 1);
        String folder = KIND_FOLDER.get(kind);
        if (folder == null) {
            throw new ToolException("kind '" + kind + "' does not support templates (supported: "
                    + KIND_FOLDER.keySet() + ")");
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String mdoRel = "src/" + folder + "/" + owner + "/" + owner + ".mdo";
        IFile mdoFile = project.getFile(mdoRel);
        if (!mdoFile.exists()) {
            throw new ToolException("owner .mdo not found at " + mdoRel
                    + " (create the owner first, e.g. create_external_object / create_md_object)");
        }

        List<Integer> columns = parseColumns(args.get("columns"));
        List<Row>     rows     = parseRows(args.get("rows"));
        String mxlx = builder.build(areaName, columns, rows);

        String mxlxRel = "src/" + folder + "/" + owner + "/Templates/" + templateName + "/Template.mxlx";
        IFile mxlxFile = project.getFile(mxlxRel);
        boolean mxlxCreated = false;
        try {
            byte[] bytes = mxlx.getBytes(StandardCharsets.UTF_8);
            if (!mxlxFile.exists()) {
                ensureParents(mxlxFile);
                mxlxFile.create(new ByteArrayInputStream(bytes), false, new NullProgressMonitor());
                mxlxCreated = true;
            } else if (overwrite) {
                mxlxFile.setContents(new ByteArrayInputStream(bytes), true, true, new NullProgressMonitor());
                mxlxCreated = true;
            }
        } catch (CoreException e) {
            throw new ToolException("failed to write .mxlx at " + mxlxRel + ": " + e.getMessage());
        }

        boolean mdoChanged = mdoEditor.addSpreadsheetTemplate(mdoFile, templateName, synonym);

        if (bmModelManager != null) {
            try { bmModelManager.waitModelSynchronization(project); }
            catch (Throwable ignored) { /* best-effort */ }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ownerFqn",     ownerFqn);
        result.put("templateName", templateName);
        result.put("mxlxPath",     mxlxRel);
        result.put("mdoPath",      mdoRel);
        result.put("mxlxCreated",  mxlxCreated);
        result.put("mdoChanged",   mdoChanged);
        return result;
    }

    // --- parsing ---

    private static List<Integer> parseColumns(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        List<Integer> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Number n) out.add(n.intValue());
        }
        return out.isEmpty() ? null : out;
    }

    @SuppressWarnings("unchecked")
    private static List<Row> parseRows(Object raw) throws ToolException {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        List<Row> rows = new ArrayList<>(list.size());
        for (Object ro : list) {
            if (!(ro instanceof Map<?, ?> rm)) {
                throw new ToolException("each row must be an object with 'cells'");
            }
            Object cellsRaw = rm.get("cells");
            List<Cell> cells = new ArrayList<>();
            if (cellsRaw instanceof List<?> cl) {
                for (Object co : cl) {
                    if (!(co instanceof Map<?, ?> cm)) {
                        throw new ToolException("each cell must be an object");
                    }
                    cells.add(parseCell((Map<String, Object>) cm));
                }
            }
            if (cells.isEmpty()) {
                cells.add(new Cell(null, null, 1, 1, false,
                        MxlxTemplateBuilder.DEFAULT_SIZE, "Left", "Top", true));
            }
            rows.add(new Row(cells));
        }
        return rows;
    }

    private static Cell parseCell(Map<String, Object> m) {
        String text  = m.get("text")      instanceof String s ? s : null;
        String param = m.get("parameter") instanceof String s ? s : null;
        int span     = intOr(m.get("span"), 1);
        int rowSpan  = intOr(m.get("rowSpan"), 1);
        boolean bold = Boolean.TRUE.equals(m.get("bold"));
        int size     = intOr(m.get("size"), MxlxTemplateBuilder.DEFAULT_SIZE);
        String align = alignOf(m.get("align"));
        String valign = valignOf(m.get("valign"));
        // wrap по умолчанию true; явный false отключает перенос
        boolean wrap = !Boolean.FALSE.equals(m.get("wrap"));
        return new Cell(text, param, span, rowSpan, bold, size, align, valign, wrap);
    }

    private static int intOr(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static String alignOf(Object o) {
        if (!(o instanceof String s)) return "Left";
        return switch (s.toLowerCase()) {
            case "center"  -> "Center";
            case "right"   -> "Right";
            case "justify" -> "Justify";
            default        -> "Left";
        };
    }

    private static String valignOf(Object o) {
        if (!(o instanceof String s)) return "Top";
        return switch (s.toLowerCase()) {
            case "center" -> "Center";
            case "bottom" -> "Bottom";
            default       -> "Top";
        };
    }

    private static void ensureParents(IFile file) throws CoreException {
        if (file.getParent() instanceof IFolder folder) ensureFolder(folder);
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
