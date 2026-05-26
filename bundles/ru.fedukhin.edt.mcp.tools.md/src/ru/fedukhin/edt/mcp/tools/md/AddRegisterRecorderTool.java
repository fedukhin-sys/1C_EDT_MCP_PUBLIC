package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
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
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;

/**
 * {@code add_register_recorder} — связывает Document как регистратор Register'а.
 *
 * <p>Args: {@code { project, register, document }}.
 *  <ul>
 *    <li>{@code register} — fqn вида {@code "AccumulationRegister.X"} (поддерживается только AccReg на v1.10.2;
 *        InformationRegister с writeMode=RecorderSubordinate — будущий follow-up).</li>
 *    <li>{@code document} — fqn вида {@code "Document.Y"}.</li>
 *  </ul>
 * <p>Result: {@code { register, document, registerMdoPath, documentMdoPath, recorderAdded, registerRecordAdded }}.
 *
 * <p>Двусторонняя запись (Phase B 2026-05-18 показала, что обе стороны нужны):
 *  <ol>
 *    <li>{@code <recorders>Document.Y</recorders>} в .mdo регистра.</li>
 *    <li>{@code <registerRecords>AccumulationRegister.X</registerRecords>} в .mdo документа.</li>
 *  </ol>
 * Без второй стороны EDT не считает документ регистратором — движения по регистру из
 * {@code ОбработкаПроведения} не оформляются.
 *
 * <p>Идемпотентно: если связь уже есть — soft-noop (флаги в response false).
 */
public final class AddRegisterRecorderTool implements IMcpTool {

    /** Register-kinds, поддерживаемые на v1.10.2. */
    private static final Map<String, String> REGISTER_FOLDER = Map.of(
            "AccumulationRegister", "AccumulationRegisters",
            "AccountingRegister",   "AccountingRegisters",
            "CalculationRegister",  "CalculationRegisters",
            "InformationRegister",  "InformationRegisters"
    );
    /** Document folder. */
    private static final String DOCUMENT_FOLDER = "Documents";
    private static final Set<String> ALLOWED_DOC_KINDS = Set.of("Document");

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdoFileEditor           mdoEditor;
    private final IBmModelManager         bmModelManager;

    @Inject
    public AddRegisterRecorderTool(MdoFileEditor mdoEditor, IBmModelManager bmModelManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), mdoEditor, bmModelManager);
    }

    /** Test seam (без bmModelManager — sync is no-op). */
    public AddRegisterRecorderTool(Supplier<IWorkspaceRoot> rootSupplier, MdoFileEditor mdoEditor) {
        this(rootSupplier, mdoEditor, null);
    }

    public AddRegisterRecorderTool(Supplier<IWorkspaceRoot> rootSupplier,
                                   MdoFileEditor mdoEditor, IBmModelManager bmModelManager) {
        this.rootSupplier   = rootSupplier;
        this.mdoEditor      = mdoEditor;
        this.bmModelManager = bmModelManager;
    }

    @Override public String name()        { return "add_register_recorder"; }
    @Override public String description() {
        return "Link a Document as a recorder of an AccumulationRegister / AccountingRegister / "
             + "CalculationRegister / InformationRegister (writes both <recorders> on register and "
             + "<registerRecords> on document; idempotent).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",  str);
        props.put("register", str);
        props.put("document", str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "register", "document"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String registerFqn = requireString(args, "register");
        String documentFqn = requireString(args, "document");

        String regKind = kindOf(registerFqn);
        String regName = nameOf(registerFqn);
        String regFolder = REGISTER_FOLDER.get(regKind);
        if (regFolder == null) {
            throw new ToolException("register must be one of " + REGISTER_FOLDER.keySet()
                    + ", got: " + registerFqn);
        }

        String docKind = kindOf(documentFqn);
        String docName = nameOf(documentFqn);
        if (!ALLOWED_DOC_KINDS.contains(docKind)) {
            throw new ToolException("document must be 'Document.X', got: " + documentFqn);
        }

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        try { project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); }
        catch (CoreException ignored) { /* best-effort */ }

        String regMdoPath = "src/" + regFolder + "/" + regName + "/" + regName + ".mdo";
        String docMdoPath = "src/" + DOCUMENT_FOLDER + "/" + docName + "/" + docName + ".mdo";
        IFile regMdo = project.getFile(regMdoPath);
        IFile docMdo = project.getFile(docMdoPath);
        if (!regMdo.exists()) {
            throw new ToolException("register .mdo not found at " + regMdoPath);
        }
        if (!docMdo.exists()) {
            throw new ToolException("document .mdo not found at " + docMdoPath);
        }

        boolean recorderAdded       = mdoEditor.addRecorder(regMdo, documentFqn);
        boolean registerRecordAdded = mdoEditor.addRegisterRecord(docMdo, registerFqn);

        if (bmModelManager != null) {
            try { bmModelManager.waitModelSynchronization(project); }
            catch (Throwable ignored) { /* best-effort */ }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("register",            registerFqn);
        result.put("document",            documentFqn);
        result.put("registerMdoPath",     regMdoPath);
        result.put("documentMdoPath",     docMdoPath);
        result.put("recorderAdded",       recorderAdded);
        result.put("registerRecordAdded", registerRecordAdded);
        return result;
    }

    private static String kindOf(String fqn) throws ToolException {
        int dot = fqn == null ? -1 : fqn.indexOf('.');
        if (dot <= 0 || dot == fqn.length() - 1) {
            throw new ToolException("fqn '" + fqn + "' must be in form Kind.Name");
        }
        return fqn.substring(0, dot);
    }

    private static String nameOf(String fqn) {
        return fqn.substring(fqn.indexOf('.') + 1);
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
