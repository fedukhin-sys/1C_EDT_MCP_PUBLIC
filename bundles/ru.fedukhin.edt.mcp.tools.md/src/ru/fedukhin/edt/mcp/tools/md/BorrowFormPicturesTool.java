package ru.fedukhin.edt.mcp.tools.md;

import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectBorrower;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;

/**
 * {@code borrow_form_pictures} — Stage 10: auto-discovery + borrow всех
 * {@code CommonPicture}-ссылок, на которые ссылается заимствованная Form
 * (через {@code <picture>CommonPicture.X</picture>} в Form.form/Form.xml).
 *
 * <p>Закрывает spike #2 из {@code [[spike-use-as-is-defaultform]]}: deploy_project
 * валится с XDTO-ошибкой «Несоответствие свойства Picture» когда adopted CommonForm
 * (или Form вложенная в parent) ссылается на CommonPicture, которая отсутствует
 * в extension Configuration.mdo {@code <commonPictures>} списке.
 *
 * <p>Args: {@code { project, parentFqn }}
 * <ul>
 *   <li>{@code parentFqn}:
 *     <ul>
 *       <li>{@code "CommonForm.X"} — adopted CommonForm в extension;</li>
 *       <li>{@code "Catalog.X.Form.Y"} / {@code "Document.X.Form.Y"} / etc. —
 *           sub-borrowed форма в adopted parent.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Result: {@code { project, parentFqn, formPath, found, borrowed, alreadyPresent }},
 * где {@code found} — все уникальные CommonPicture refs из Form.form;
 * {@code borrowed} — те, которые были вновь заимствованы (CommonPictures/X/X.mdo создан +
 * Configuration.mdo обновлён); {@code alreadyPresent} — те, для которых extension уже
 * имеет skeleton.
 *
 * <p>Идемпотентно: уже-borrowed pictures не создаются заново.
 *
 * <p>Pattern для поиска: {@code <picture>CommonPicture.<name></picture>}. Регистр
 * не игнорируется. Whitespace вокруг tag-content допустим.
 */
public final class BorrowFormPicturesTool implements IMcpTool {

    /** Pattern: <picture>CommonPicture.NAME</picture>. NAME = любые символы кроме '<'. */
    private static final Pattern PICTURE_REF =
            Pattern.compile("<picture>\\s*CommonPicture\\.([^<\\s]+?)\\s*</picture>");

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdObjectBorrower         borrower;

    @Inject
    public BorrowFormPicturesTool(MdObjectBorrower borrower) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), borrower);
    }

    /** Test seam. */
    public BorrowFormPicturesTool(Supplier<IWorkspaceRoot> rootSupplier, MdObjectBorrower borrower) {
        this.rootSupplier = rootSupplier;
        this.borrower     = borrower;
    }

    @Override public String name()        { return "borrow_form_pictures"; }
    @Override public String description() {
        return "Auto-discover CommonPicture refs in an adopted Form's Form.form and borrow "
             + "each missing CommonPicture (skeleton .mdo + <commonPictures> entry). "
             + "formFqn = the form's own FQN: 'CommonForm.X' or 'Kind.Owner.Form.Y' "
             + "(not the parent MdObject). Idempotent.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",   Map.of("type", "string"));
        props.put("formFqn",   Map.of("type", "string",
                "description", "Form FQN: 'CommonForm.X' or 'Kind.Owner.Form.Y'"));
        props.put("parentFqn", Map.of("type", "string",
                "description", "Deprecated alias of formFqn"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        // BUG-10: the argument is the form's own FQN — "formFqn" is the correct
        // name; "parentFqn" is kept as a deprecated alias for back-compat.
        Object formArg = args.get("formFqn") != null ? args.get("formFqn") : args.get("parentFqn");
        if (!(formArg instanceof String formFqn) || formFqn.isEmpty()) {
            throw new ToolException("'formFqn' must be a non-empty string");
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String formRel = resolveFormPath(formFqn);
        IFile formFile = project.getFile(formRel);
        if (!formFile.exists()) {
            throw new ToolException("Form.form not found at " + formRel
                    + " (is " + formFqn + " already borrowed?)");
        }

        Set<String> found;
        try (InputStream is = formFile.getContents()) {
            found = scanPictureRefs(is);
        } catch (CoreException | IOException e) {
            throw new ToolException("failed to read " + formRel + ": " + e.getMessage());
        }

        List<String> borrowed = new ArrayList<>();
        List<String> alreadyPresent = new ArrayList<>();

        for (String pictureName : found) {
            IFile pictureMdo = project.getFile(
                    "src/CommonPictures/" + pictureName + "/" + pictureName + ".mdo");
            if (pictureMdo.exists()) {
                alreadyPresent.add(pictureName);
                continue;
            }
            // Заимствуем CommonPicture (skeleton .mdo + <commonPictures> ref в Configuration.mdo).
            try {
                borrower.borrow(project, "CommonPicture." + pictureName);
                borrowed.add(pictureName);
            } catch (ToolException ex) {
                // Если конкретная картинка не нашлась в base (например, опечатка в Form.form) —
                // не падаем; залогируем как skipped.
                if (ex.getMessage() != null && ex.getMessage().contains("not found in base")) {
                    alreadyPresent.add(pictureName + " [base-missing]");
                } else {
                    throw ex;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project",        projectName);
        result.put("formFqn",        formFqn);
        result.put("formPath",       formRel);
        result.put("found",          new ArrayList<>(found));
        result.put("borrowed",       borrowed);
        result.put("alreadyPresent", alreadyPresent);
        return result;
    }

    /**
     * Парсит fqn вида {@code "CommonForm.X"} или {@code "Kind.Owner.Form.Y"} и возвращает
     * workspace-relative путь к Form.form.
     */
    public static String resolveFormPath(String formFqn) throws ToolException {
        if (formFqn == null || formFqn.isEmpty()) {
            throw new ToolException("formFqn required");
        }
        String[] parts = formFqn.split("\\.");
        if (parts.length == 2 && "CommonForm".equals(parts[0])) {
            return "src/CommonForms/" + parts[1] + "/Form.form";
        }
        if (parts.length == 4 && "Form".equals(parts[2])) {
            String folder = pluralForKind(parts[0]);
            return "src/" + folder + "/" + parts[1] + "/Forms/" + parts[3] + "/Form.form";
        }
        throw new ToolException("formFqn must be 'CommonForm.X' or 'Kind.Owner.Form.Y', got: "
                + formFqn);
    }

    /** Папка kind'а в {@code src/} — из единого реестра, а не из локальной копии карты. */
    private static String pluralForKind(String kind) throws ToolException {
        String folder = MdObjectRegistry.folderName(kind);
        if (folder == null) {
            throw new ToolException("unknown kind '" + kind + "' for Form parent");
        }
        return folder;
    }

    /** Public API for unit-tests: scan a Form.form input stream and collect unique picture names. */
    public static Set<String> scanPictureRefs(InputStream in) throws IOException {
        byte[] buf = in.readAllBytes();
        String content = new String(buf, StandardCharsets.UTF_8);
        Set<String> unique = new LinkedHashSet<>();
        Matcher m = PICTURE_REF.matcher(content);
        while (m.find()) {
            unique.add(m.group(1));
        }
        return unique;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
