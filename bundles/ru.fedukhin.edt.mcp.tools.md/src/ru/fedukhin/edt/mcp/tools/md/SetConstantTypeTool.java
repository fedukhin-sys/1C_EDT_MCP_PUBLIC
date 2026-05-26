package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringParser;

/**
 * {@code set_constant_type} — устанавливает тип значения Constant через DOM.
 *
 * <p>Args: {@code { project, fqn, type (string|string[]) }}.
 * <ul>
 *   <li>{@code fqn} — {@code "Constant.<Name>"}.</li>
 *   <li>{@code type} — type-выражение или массив для составного типа:
 *       {@code "String(100)"}, {@code "Number(15,2)"}, {@code "Date"}, {@code "Boolean"},
 *       {@code "CatalogRef.X"}, {@code "DocumentRef.Y"}, {@code "EnumRef.Z"},
 *       {@code "AnyRef"}, {@code "UUID"} либо массив таких выражений.</li>
 * </ul>
 * <p>Result: {@code { fqn, type, mdoPath, replaced }}.
 *
 * <p>Идемпотентно: если предыдущий {@code <type>} был — он перезаписывается.
 * Для остальных MdObject'ов с feature {@code type=TypeDescription} (например, Attribute)
 * — отдельные API ({@code add_attribute}, {@code rename_attribute}).
 */
public final class SetConstantTypeTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdoFileEditor           mdoEditor;
    private final TypeStringParser        parser;
    private final IBmModelManager         bmModelManager;

    @Inject
    public SetConstantTypeTool(MdoFileEditor mdoEditor, TypeStringParser parser,
                               IBmModelManager bmModelManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), mdoEditor, parser, bmModelManager);
    }

    /** Test seam. */
    public SetConstantTypeTool(Supplier<IWorkspaceRoot> rootSupplier,
                               MdoFileEditor mdoEditor, TypeStringParser parser) {
        this(rootSupplier, mdoEditor, parser, null);
    }

    public SetConstantTypeTool(Supplier<IWorkspaceRoot> rootSupplier,
                               MdoFileEditor mdoEditor, TypeStringParser parser,
                               IBmModelManager bmModelManager) {
        this.rootSupplier   = rootSupplier;
        this.mdoEditor      = mdoEditor;
        this.parser         = parser;
        this.bmModelManager = bmModelManager;
    }

    @Override public String name()        { return "set_constant_type"; }
    @Override public String description() {
        return "Set the value type of a Constant. type=string (single) or array (composite). "
             + "Supported: String(N), Number(P,S), Date, Boolean, CatalogRef.X, DocumentRef.Y, "
             + "EnumRef.Z, AnyRef, UUID. Replace-or-add semantics on the .mdo <type> block.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", str);
        props.put("fqn",     str);
        props.put("type",    Map.of());
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "fqn", "type"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");
        Object typeArg     = args.get("type");

        if (!fqn.startsWith("Constant.")) {
            throw new ToolException("fqn must be 'Constant.<Name>', got: " + fqn);
        }
        String name = fqn.substring("Constant.".length());
        if (name.isEmpty()) {
            throw new ToolException("constant name is empty in fqn: " + fqn);
        }

        List<String> typeExprs = collectTypes(typeArg);
        if (typeExprs.isEmpty()) {
            throw new ToolException("'type' must be a non-empty string or array of strings");
        }
        // Validate each expression by re-parsing — пусть parser отлавливает мусор
        for (String t : typeExprs) parser.parseOne(t);

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String mdoPath = "src/Constants/" + name + "/" + name + ".mdo";
        IFile mdoFile = project.getFile(mdoPath);
        if (!mdoFile.exists()) {
            throw new ToolException("constant .mdo not found at " + mdoPath);
        }

        boolean replaced = mdoEditor.setConstantType(mdoFile, typeExprs);

        if (bmModelManager != null) {
            try { bmModelManager.waitModelSynchronization(project); }
            catch (Throwable ignored) { /* best-effort */ }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn",      fqn);
        result.put("type",     typeArg);
        result.put("mdoPath",  mdoPath);
        result.put("replaced", replaced);
        return result;
    }

    private static List<String> collectTypes(Object typeArg) throws ToolException {
        if (typeArg instanceof String s) {
            return s.isEmpty() ? List.of() : List.of(s);
        }
        if (typeArg instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && !s.isEmpty()) out.add(s);
            }
            return out;
        }
        throw new ToolException("'type' must be a string or array of strings");
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
