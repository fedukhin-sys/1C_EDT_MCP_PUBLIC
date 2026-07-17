package ru.fedukhin.edt.mcp.tools.md;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * {@code list_attributes} — перечисляет attributes/dimensions/resources MdObject.
 *
 * <p>Args: {@code { project, fqn }}.
 * <p>Result: {@code { attributes: AttributeInfo[], tabularSections: TabularSectionInfo[],
 * values: EnumValueInfo[] }}, где {@code TabularSectionInfo = { name, synonym, attributes[] }},
 * {@code EnumValueInfo = { name, synonym, comment }}. Плоский {@code attributes} несёт только
 * реквизиты верхнего уровня — колонки ТЧ в него не подмешиваются; {@code values} непусты только
 * у Enum. Все три ключа присутствуют всегда.
 *
 * <p>BUG-16: читает напрямую из {@code .mdo} на диске (а не из BM-модели), потому
 * что BM-модель отстаёт от диска сразу после DOM-route мутации ({@code add_attribute})
 * и возвращала устаревший/неполный список.
 */
public final class ListAttributesTool implements IMcpTool {

    /**
     * Kind'ы, чей .mdo вообще несёт что-то перечислимое: реквизиты/измерения/ресурсы,
     * табличные части либо значения перечисления. Имя папки берётся из
     * {@link MdObjectRegistry#folderName} — здесь только «какие kind'ы читаем».
     */
    private static final Set<String> READABLE_KINDS = Set.of(
            "Catalog", "Document", "BusinessProcess", "Task",
            "ChartOfAccounts", "ChartOfCalculationTypes", "ChartOfCharacteristicTypes",
            "ExchangePlan", "AccumulationRegister", "InformationRegister", "Enum");

    /** kind'ы, чей .mdo несёт не реквизиты, а значения перечисления. */
    private static final String ENUM_KIND = "Enum";

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdoFileEditor            mdoEditor;

    @Inject
    public ListAttributesTool(MdoFileEditor mdoEditor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), mdoEditor);
    }

    /** Test seam. */
    public ListAttributesTool(Supplier<IWorkspaceRoot> rootSupplier, MdoFileEditor mdoEditor) {
        this.rootSupplier = rootSupplier;
        this.mdoEditor    = mdoEditor;
    }

    @Override public String name()        { return "list_attributes"; }

    @Override public String description() {
        return "List top-level attributes/dimensions/resources of an MdObject, its tabularSections "
             + "with their columns, and — for an Enum — its values";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> strType = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", strType);
        props.put("fqn",     strType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "fqn"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");

        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot == fqn.length() - 1) {
            throw new ToolException("fqn '" + fqn + "' must be in form Kind.Name");
        }
        String kind = fqn.substring(0, dot);
        String name = fqn.substring(dot + 1);

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        String folder = READABLE_KINDS.contains(kind) ? MdObjectRegistry.folderName(kind) : null;
        if (folder == null) {
            // kind has no attribute-bearing .mdo (CommonModule, Subsystem, ...) — empty lists
            return Map.of("attributes", List.of(), "tabularSections", List.of(), "values", List.of());
        }

        // BUG-16: refreshLocal so Eclipse sees a .mdo a mutation just wrote, then
        // read attributes straight from disk — never stale, unlike the BM model.
        try {
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        } catch (CoreException ignored) {
            // best-effort
        }

        String mdoRel = "src/" + folder + "/" + name + "/" + name + ".mdo";
        IFile mdoFile = project.getFile(mdoRel);
        if (!mdoFile.exists()) {
            throw new ToolException("MdObject '.mdo' not found at " + mdoRel);
        }
        if (ENUM_KIND.equals(kind)) {
            // У перечисления нет ни реквизитов, ни ТЧ — только значения.
            return Map.of(
                "attributes",      List.of(),
                "tabularSections", List.of(),
                "values",          mdoEditor.readEnumValues(mdoFile));
        }
        return Map.of(
            "attributes",      mdoEditor.readAttributes(mdoFile, kind),
            "tabularSections", mdoEditor.readTabularSections(mdoFile),
            "values",          List.of());
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
