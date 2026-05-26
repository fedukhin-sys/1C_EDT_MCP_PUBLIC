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
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectKind;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor.AttrLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringParser;

/**
 * {@code set_md_type} — устанавливает тип произвольного Attribute/Dimension/Resource
 * в parent .mdo через DOM (replace-or-add).
 *
 * <p>Args: {@code { project, fqn, type (string|string[]) }}.
 * <p>Result: {@code { fqn, type, mdoPath, replaced }}.
 *
 * <p>Поддерживаемые формы FQN:
 * <ul>
 *   <li>{@code Catalog.X.Attribute.Y} / {@code Document.X.Attribute.Y} — root-level attribute.</li>
 *   <li>{@code Catalog.X.TabularSection.TS.Y} / {@code Document.X.TabularSection.TS.Y} — TS column.</li>
 *   <li>{@code InformationRegister.X.Dimension.Y} / {@code InformationRegister.X.Resource.Y} /
 *       {@code InformationRegister.X.Attribute.Y}.</li>
 *   <li>{@code AccumulationRegister.X.Dimension.Y} / {@code AccumulationRegister.X.Resource.Y} /
 *       {@code AccumulationRegister.X.Attribute.Y}.</li>
 *   <li>{@code Constant.X} — fallback на {@link SetConstantTypeTool}-логику.</li>
 * </ul>
 *
 * <p>Идемпотентно: если предыдущий {@code <type>} был — он перезаписывается.
 * Если attribute с указанным именем не существует в .mdo — {@link ToolException}
 * (используй {@code add_attribute} / {@code add_tabular_section_attribute} для создания).
 */
public final class SetMdTypeTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdoFileEditor           mdoEditor;
    private final TypeStringParser        parser;
    private final MdObjectRegistry        registry;
    private final IBmModelManager         bmModelManager;

    @Inject
    public SetMdTypeTool(MdoFileEditor mdoEditor, TypeStringParser parser,
                         MdObjectRegistry registry, IBmModelManager bmModelManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(),
                mdoEditor, parser, registry, bmModelManager);
    }

    /** Test seam. */
    public SetMdTypeTool(Supplier<IWorkspaceRoot> rootSupplier,
                         MdoFileEditor mdoEditor, TypeStringParser parser,
                         MdObjectRegistry registry) {
        this(rootSupplier, mdoEditor, parser, registry, null);
    }

    public SetMdTypeTool(Supplier<IWorkspaceRoot> rootSupplier,
                         MdoFileEditor mdoEditor, TypeStringParser parser,
                         MdObjectRegistry registry, IBmModelManager bmModelManager) {
        this.rootSupplier   = rootSupplier;
        this.mdoEditor      = mdoEditor;
        this.parser         = parser;
        this.registry       = registry;
        this.bmModelManager = bmModelManager;
    }

    @Override public String name()        { return "set_md_type"; }
    @Override public String description() {
        return "Set the type of an Attribute/Dimension/Resource on an MdObject via DOM. "
             + "fqn forms: 'Catalog.X.Attribute.Y', 'Document.X.TabularSection.TS.Y', "
             + "'InformationRegister.X.Dimension.Y', 'AccumulationRegister.X.Resource.Y', "
             + "'Constant.X' (fallback). type=string or array (composite). Same type-expressions "
             + "as set_constant_type: String(N), Number(P,S), Date, Boolean, CatalogRef.X, "
             + "DocumentRef.Y, EnumRef.Z, AnyRef, UUID. Replace-or-add semantics.";
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

    /** Распарсенный FQN. */
    public record ParsedFqn(String kind, String objectName, AttrLocator locator, boolean isConstant) { }

    public static ParsedFqn parseFqn(String fqn) throws ToolException {
        String[] parts = fqn.split("\\.");
        if (parts.length < 2) {
            throw new ToolException("malformed fqn: " + fqn);
        }
        String kind = parts[0];
        String objName = parts[1];
        if (objName.isEmpty()) {
            throw new ToolException("object name is empty in fqn: " + fqn);
        }
        if ("Constant".equals(kind)) {
            if (parts.length != 2) {
                throw new ToolException("Constant fqn must be 'Constant.<Name>', got: " + fqn);
            }
            return new ParsedFqn(kind, objName, null, true);
        }
        if (parts.length == 4) {
            // Kind.Name.Role.AttrName
            String role = parts[2];
            String attrName = parts[3];
            if (attrName.isEmpty()) throw new ToolException("empty attribute name in fqn: " + fqn);
            String tag = switch (role) {
                case "Attribute" -> "attributes";
                case "Dimension" -> "dimensions";
                case "Resource"  -> "resources";
                default -> throw new ToolException(
                        "role must be Attribute|Dimension|Resource|TabularSection, got: " + role);
            };
            return new ParsedFqn(kind, objName, new AttrLocator(tag, null, attrName), false);
        }
        if (parts.length == 5 && "TabularSection".equals(parts[2])) {
            // Kind.Name.TabularSection.TS.AttrName
            String tsName = parts[3];
            String attrName = parts[4];
            if (tsName.isEmpty() || attrName.isEmpty()) {
                throw new ToolException("empty tabular section / attribute name in fqn: " + fqn);
            }
            return new ParsedFqn(kind, objName, new AttrLocator("attributes", tsName, attrName), false);
        }
        throw new ToolException("malformed fqn: " + fqn
                + " (expected 'Kind.Name.Role.AttrName' or 'Kind.Name.TabularSection.TS.AttrName')");
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");
        Object typeArg     = args.get("type");

        ParsedFqn p = parseFqn(fqn);

        List<String> typeExprs = collectTypes(typeArg);
        if (typeExprs.isEmpty()) {
            throw new ToolException("'type' must be a non-empty string or array of strings");
        }
        for (String t : typeExprs) parser.parseOne(t);

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        MdObjectKind kindMeta = registry.get(p.kind);
        if (kindMeta == null || kindMeta.folderName() == null) {
            throw new ToolException("kind '" + p.kind + "' is not supported by set_md_type");
        }
        String mdoPath = "src/" + kindMeta.folderName() + "/" + p.objectName + "/" + p.objectName + ".mdo";
        IFile mdoFile = project.getFile(mdoPath);
        if (!mdoFile.exists()) {
            throw new ToolException(".mdo not found at " + mdoPath);
        }

        boolean replaced;
        if (p.isConstant) {
            replaced = mdoEditor.setConstantType(mdoFile, typeExprs);
        } else {
            replaced = mdoEditor.setAttributeType(mdoFile, p.locator, typeExprs);
        }

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
