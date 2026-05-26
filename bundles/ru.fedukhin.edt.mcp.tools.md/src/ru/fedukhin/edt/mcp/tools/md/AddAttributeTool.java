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
import ru.fedukhin.edt.mcp.tools.md.internal.ParsedType;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringParser;

/**
 * {@code add_attribute} — добавляет attribute/dimension/resource к MdObject.
 *
 * <p>Args: {@code { project, fqn, name, type (string|string[]), role?, synonym?, comment? }}.
 * <p>Result: {@code { fqn, attributeName, role, type }}.
 *
 * <p>Все supported kinds (Catalog/Document/Register-kinds) роутятся через
 * {@link MdoFileEditor} (DOM-edit). Старая BM-route (через AttributeFactory) удалена в v1.10.2,
 * т.к. для REF-типов Dimension она писала пустой {@code <type/>} (Bug #2).
 */
public final class AddAttributeTool implements IMcpTool {

    /** kind → folder name in src/. */
    private static final Map<String, String> KIND_FOLDER = Map.ofEntries(
            Map.entry("Catalog",                    "Catalogs"),
            Map.entry("Document",                   "Documents"),
            Map.entry("BusinessProcess",            "BusinessProcesses"),
            Map.entry("Task",                       "Tasks"),
            Map.entry("ChartOfAccounts",            "ChartsOfAccounts"),
            Map.entry("ChartOfCalculationTypes",    "ChartsOfCalculationTypes"),
            Map.entry("ChartOfCharacteristicTypes", "ChartsOfCharacteristicTypes"),
            Map.entry("AccumulationRegister",       "AccumulationRegisters"),
            Map.entry("InformationRegister",        "InformationRegisters")
    );

    /** Register-kinds — для них роль обязательна и определяет XML-tag. */
    private static final Set<String> REGISTER_KINDS =
            Set.of("AccumulationRegister", "InformationRegister");

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdObjectRegistry        registry;
    private final TypeStringParser        parser;
    private final MdoFileEditor           mdoEditor;

    @Inject
    public AddAttributeTool(MdObjectRegistry registry, TypeStringParser parser, MdoFileEditor mdoEditor) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), registry, parser, mdoEditor);
    }

    /** Test seam. */
    public AddAttributeTool(Supplier<IWorkspaceRoot> rootSupplier,
                            MdObjectRegistry registry, TypeStringParser parser, MdoFileEditor mdoEditor) {
        this.rootSupplier = rootSupplier;
        this.registry     = registry;
        this.parser       = parser;
        this.mdoEditor    = mdoEditor;
    }

    @Override public String name()        { return "add_attribute"; }
    @Override public String description() { return "Add an attribute/dimension/resource to an MdObject"; }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> strType = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", strType);
        props.put("fqn",     strType);
        props.put("name",    strType);
        props.put("type",    Map.of());
        props.put("role",    strType);
        props.put("synonym", strType);
        props.put("comment", strType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "fqn", "name", "type"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");
        String name        = requireString(args, "name");
        Object typeArg     = args.get("type");
        String role        = args.get("role") instanceof String s ? s : null;

        int dot = fqn.indexOf('.');
        if (dot <= 0) {
            throw new ToolException("fqn '" + fqn + "' must be in form Kind.Name");
        }
        String kind = fqn.substring(0, dot);

        var kindMeta = registry.get(kind);
        if (kindMeta == null || !kindMeta.supportsAttributes()) {
            throw new ToolException("kind '" + kind + "' does not support attributes");
        }

        String folder = KIND_FOLDER.get(kind);
        if (folder == null) {
            throw new ToolException("kind '" + kind + "' is registered but has no DOM-folder mapping");
        }

        // Parse type for validation + fallback re-stringify
        List<ParsedType> parsed;
        if (typeArg instanceof String s) {
            parsed = List.of(parser.parseOne(s));
        } else if (typeArg instanceof List<?> list) {
            String[] arr = list.stream()
                    .filter(x -> x instanceof String)
                    .map(x -> (String) x)
                    .toArray(String[]::new);
            parsed = parser.parseMany(arr);
        } else {
            throw new ToolException("'type' must be a string or array of strings");
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        String ownerName = fqn.substring(dot + 1);
        String mdoRel = "src/" + folder + "/" + ownerName + "/" + ownerName + ".mdo";
        IFile mdoFile = waitForMdo(project, mdoRel, /* maxMillis */ 3000);
        if (!mdoFile.exists()) {
            throw new ToolException("parent MdObject '.mdo' not found at " + mdoRel);
        }

        List<String> typeExprList = collectTypeExpressions(typeArg, parsed);
        String effectiveRole;
        if (REGISTER_KINDS.contains(kind)) {
            if (role == null) {
                throw new ToolException(
                        "register kinds require 'role' parameter: \"Attribute\" | \"Dimension\" | \"Resource\"");
            }
            if (!(role.equals("Attribute") || role.equals("Dimension") || role.equals("Resource"))) {
                throw new ToolException("unknown role '" + role + "' for kind " + kind);
            }
            mdoEditor.addRegisterAttribute(mdoFile,
                    new MdoFileEditor.RegisterAttrSpec(role, name, typeExprList));
            effectiveRole = role;
        } else {
            if (role != null && !"Attribute".equals(role)) {
                throw new ToolException("kind '" + kind + "' supports only role=Attribute");
            }
            mdoEditor.addMdObjectAttribute(mdoFile,
                    new MdoFileEditor.MdAttributeSpec(name, typeExprList.get(0)));
            effectiveRole = "Attribute";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn",           fqn);
        result.put("attributeName", name);
        result.put("role",          effectiveRole);
        result.put("type",          typeArg);
        return result;
    }

    /**
     * Polling helper для Bug #3 race-condition: после create_md_object .mdo появляется
     * на диске асинхронно (BmPersistentExecutor + GlobalEditingContext). Делаем retry
     * с backoff до {@code maxMillis}, между попытками — refreshLocal на project.
     */
    private static IFile waitForMdo(IProject project, String relPath, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        long sleep = 50L;
        IFile file;
        while (true) {
            try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
            catch (CoreException ignored) { /* best-effort */ }
            file = project.getFile(relPath);
            if (file != null && file.exists()) return file;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return file; // return whatever for the .exists() check
            long actual = Math.min(sleep, remaining);
            try { Thread.sleep(actual); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return file;
            }
            sleep = Math.min(sleep * 2, 1600L);
        }
    }

    private static List<String> collectTypeExpressions(Object typeArg, List<ParsedType> parsed) {
        if (typeArg instanceof String s) {
            return List.of(s);
        }
        if (typeArg instanceof List<?> list) {
            List<String> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s) out.add(s);
            }
            if (!out.isEmpty()) return out;
        }
        if (parsed != null && !parsed.isEmpty()) {
            List<String> out = new java.util.ArrayList<>(parsed.size());
            for (ParsedType t : parsed) out.add(stringifyParsed(t));
            return out;
        }
        return List.of("String");
    }

    private static String stringifyParsed(ParsedType t) {
        String base;
        switch (t.kind()) {
            case STRING:  base = "String";  break;
            case NUMBER:  base = "Number";  break;
            case DATE:    return "Date";
            case BOOLEAN: return "Boolean";
            case ANY_REF: return "AnyRef";
            case UUID:    return "UUID";
            case REF:     return t.refKind() + "Ref." + t.refName();
            default:      return t.kind().toString();
        }
        if (t.length() != null && t.precision() != null) return base + "(" + t.length() + "," + t.precision() + ")";
        if (t.length() != null)                           return base + "(" + t.length() + ")";
        return base;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
