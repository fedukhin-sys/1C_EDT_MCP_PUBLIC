package ru.fedukhin.edt.mcp.tools.md;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;

/**
 * {@code borrow_form} — заимствование (sub-borrow) Form'ы из base parent
 * в уже-adopted parent MdObject в extension (Stage 8d v2).
 *
 * <p>Args: {@code { project, parentFqn, formName }}
 * <p>Result: {@code { project, formFqn, adoptedFormUuid, baseFormUuid, mdoPath }}
 *
 * <p>Парент должен быть уже adopted через {@code borrow_md_object}. Form
 * добавляется inline в parent {@code .mdo} как {@code <forms uuid
 * extendedConfigurationObject>} с {@code objectBelonging=Adopted} +
 * {@code extension xsi:type="mdclassExtension:BasicFormExtension"} +
 * {@code <form>Extended</form>}.
 *
 * <p>Поддерживаемые parent-kinds (имеют формы): Catalog, Document,
 * DataProcessor, Report, InformationRegister, AccumulationRegister,
 * BusinessProcess, Task, ChartOfX.
 */
public final class BorrowFormTool implements IMcpTool {

    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    /**
     * Kind'ы, у объектов которых бывают собственные формы. Имя папки — из
     * {@link MdObjectRegistry#folderName}; здесь только состав.
     */
    private static final Set<String> KINDS_WITH_FORMS = Set.of(
            "Catalog", "Document", "DataProcessor", "Report",
            "InformationRegister", "AccumulationRegister", "BusinessProcess", "Task",
            "ChartOfAccounts", "ChartOfCalculationTypes", "ChartOfCharacteristicTypes");

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IV8ProjectManager        projectManager;
    private final IBmModelManager          bmModelManager;

    @Inject
    public BorrowFormTool(IV8ProjectManager projectManager, IBmModelManager bmModelManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), projectManager, bmModelManager);
    }

    public BorrowFormTool(Supplier<IWorkspaceRoot> rootSupplier,
                          IV8ProjectManager projectManager,
                          IBmModelManager bmModelManager) {
        this.rootSupplier   = rootSupplier;
        this.projectManager = projectManager;
        this.bmModelManager = bmModelManager;
    }

    @Override public String name()        { return "borrow_form"; }
    @Override public String description() {
        return "Sub-borrow a Form from base parent MdObject into extension. "
             + "parentFqn = 'Kind.Name' of the already-borrowed parent in extension.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",    str);
        props.put("parentFqn",  str);
        props.put("formName",   str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "parentFqn", "formName"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String parentFqn   = requireString(args, "parentFqn");
        String formName    = requireString(args, "formName");

        int dot = parentFqn.indexOf('.');
        if (dot <= 0 || dot == parentFqn.length() - 1) {
            throw new ToolException("parentFqn must be 'Kind.Name': '" + parentFqn + "'");
        }
        String kind = parentFqn.substring(0, dot);
        String parentName = parentFqn.substring(dot + 1);
        String folder = KINDS_WITH_FORMS.contains(kind) ? MdObjectRegistry.folderName(kind) : null;
        if (folder == null) {
            throw new ToolException("kind '" + kind + "' does not support forms; supported: "
                    + KINDS_WITH_FORMS);
        }

        IProject extIProject = rootSupplier.get().getProject(projectName);
        if (extIProject == null || !extIProject.exists() || !extIProject.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }
        IV8Project extV8 = projectManager.getProject(extIProject);
        if (!(extV8 instanceof IExtensionProject extProject)) {
            throw new ToolException("project '" + projectName + "' is not an Extension project");
        }
        IConfigurationProject baseProject = extProject.getParent();
        if (baseProject == null) {
            throw new ToolException("extension '" + projectName + "' has no parent configuration");
        }
        IProject baseIProject = baseProject.getProject();

        // Adopted parent .mdo must already exist in extension
        String mdoRel = "src/" + folder + "/" + parentName + "/" + parentName + ".mdo";
        IFile extMdoFile = extIProject.getFile(mdoRel);
        if (!extMdoFile.exists()) {
            throw new ToolException("parent '" + parentFqn + "' is not borrowed in extension yet — call borrow_md_object first");
        }

        // Read base parent .mdo and find <forms uuid> with matching <name>
        IFile baseMdoFile = baseIProject.getFile(mdoRel);
        if (!baseMdoFile.exists()) {
            throw new ToolException("base parent '.mdo' not found at " + mdoRel);
        }
        Document baseDoc = parseXml(baseMdoFile);
        String baseFormUuid = findBaseFormUuid(baseDoc, formName);
        if (baseFormUuid == null) {
            throw new ToolException("form '" + formName + "' not found in base " + parentFqn);
        }

        // Modify extension parent .mdo: append <forms uuid extendedConfigurationObject>
        Document extDoc = parseXml(extMdoFile);
        Element extRoot = extDoc.getDocumentElement();

        // Dup check
        if (findFormElementByName(extRoot, formName) != null) {
            throw new ToolException("form '" + formName + "' already borrowed in extension");
        }

        String adoptedFormUuid = UUID.randomUUID().toString();
        Element forms = extDoc.createElement("forms");
        forms.setAttribute("uuid", adoptedFormUuid);
        forms.setAttribute("extendedConfigurationObject", baseFormUuid);
        appendText(extDoc, forms, "name", formName);
        appendText(extDoc, forms, "objectBelonging", "Adopted");
        Element ext = extDoc.createElement("extension");
        ext.setAttributeNS(XSI_NS, "xsi:type", "mdclassExtension:BasicFormExtension");
        appendText(extDoc, ext, "extendedConfigurationObject", "Checked");
        appendText(extDoc, ext, "form", "Extended");
        forms.appendChild(ext);

        // Insert at end of root
        extRoot.appendChild(forms);
        writeXml(extMdoFile, extDoc);

        // 2026-05-18 fix: без .form файлов на диске EDT-редактор формы падает
        // «Блокирующая». Adopted-форма требует:
        //   Forms/<name>/BaseForm/Form.form  — копия base Form.form (read-only baseline)
        //   Forms/<name>/Form.form           — extension overlay (изначально copy base)
        //   Forms/<name>/Module.bsl          — extension module (изначально пустой)
        // Module.bsl создаём пустым; .form файлы копируем 1:1 из base.
        String formFolderRel = "src/" + folder + "/" + parentName + "/Forms/" + formName;
        IFolder formFolder = extIProject.getFolder(formFolderRel);
        IFolder baseFormFolder = formFolder.getFolder("BaseForm");

        IFile baseFormFile = baseIProject.getFile(formFolderRel + "/Form.form");
        if (!baseFormFile.exists()) {
            throw new ToolException("base Form.form not found at " + baseFormFile.getFullPath());
        }

        try {
            ensureFolder(formFolder);
            ensureFolder(baseFormFolder);

            // 2026-05-18 fix #2: EDT canonical adoption НЕ копирует base 1:1.
            // Стрипает из Form.form:
            //   recursive: dataPath, handlers, commandName, cmiFragmentRecord
            //   root only: attributes, formCommands, parameters
            // Без stripping extension overlay содержит references на base BSL/data,
            // EDT-редактор показывает кучу ошибок (сравнение с canonical adoption
            // ЗаказПоставщику показало). BaseForm/Form.form и Form.form у каноника
            // ИДЕНТИЧНЫ — обе stripped версии.
            Document baseFormDoc = parseXml(baseFormFile);
            stripFormAdoption(baseFormDoc.getDocumentElement());
            byte[] strippedBytes = serializeXml(baseFormDoc);

            IFile extBaseForm = baseFormFolder.getFile("Form.form");
            IFile extForm     = formFolder.getFile("Form.form");
            IFile extModule   = formFolder.getFile("Module.bsl");

            if (extBaseForm.exists()) {
                extBaseForm.setContents(new java.io.ByteArrayInputStream(strippedBytes), true, true, new NullProgressMonitor());
            } else {
                extBaseForm.create(new java.io.ByteArrayInputStream(strippedBytes), true, new NullProgressMonitor());
            }
            if (extForm.exists()) {
                extForm.setContents(new java.io.ByteArrayInputStream(strippedBytes), true, true, new NullProgressMonitor());
            } else {
                extForm.create(new java.io.ByteArrayInputStream(strippedBytes), true, new NullProgressMonitor());
            }
            if (!extModule.exists()) {
                extModule.create(new java.io.ByteArrayInputStream(new byte[0]), true, new NullProgressMonitor());
            }
        } catch (CoreException e) {
            throw new ToolException("failed to write adopted form files: " + e.getMessage());
        }

        // BM sync
        try { extIProject.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor()); } catch (CoreException ignored) {}
        if (bmModelManager != null) {
            try { bmModelManager.waitModelSynchronization(extIProject); } catch (Throwable ignored) {}
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project",         projectName);
        result.put("formFqn",         parentFqn + ".Form." + formName);
        result.put("adoptedFormUuid", adoptedFormUuid);
        result.put("baseFormUuid",    baseFormUuid);
        result.put("mdoPath",         mdoRel);
        result.put("formFiles",       List.of(
                formFolderRel + "/BaseForm/Form.form",
                formFolderRel + "/Form.form",
                formFolderRel + "/Module.bsl"));
        return result;
    }

    private static void ensureFolder(IFolder folder) throws CoreException {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) ensureFolder(parent);
        folder.create(/*force*/ false, /*local*/ true, new NullProgressMonitor());
    }

    private static byte[] readAll(IFile file) throws java.io.IOException, CoreException {
        try (java.io.InputStream in = file.getContents()) {
            return in.readAllBytes();
        }
    }

    private static byte[] serializeXml(Document doc) throws ToolException {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(baos));
            return baos.toByteArray();
        } catch (TransformerException e) {
            throw new ToolException("failed to serialize XML: " + e.getMessage());
        }
    }

    /**
     * EDT-canonical Form.form adoption stripping (2026-05-18).
     *
     * <p>Удаляет:
     * <ul>
     *   <li>Recursive (любая глубина): dataPath, handlers, commandName, cmiFragmentRecord</li>
     *   <li>Root only: attributes, formCommands, parameters</li>
     * </ul>
     *
     * <p>Эти элементы — references на base BSL/data, которые не должны быть в
     * extension overlay. Без stripping EDT-редактор adopted-формы показывает
     * множество ошибок «процедура/реквизит не определён».
     */
    private static void stripFormAdoption(Element root) {
        // Root-only strip
        java.util.Set<String> rootOnly = java.util.Set.of("attributes", "formCommands", "parameters");
        java.util.List<Node> rootRemove = new java.util.ArrayList<>();
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && rootOnly.contains(k.getNodeName())) {
                rootRemove.add(k);
            }
        }
        for (Node n : rootRemove) root.removeChild(n);

        // Recursive strip. *DataPath: footerDataPath и rowPictureDataPath — aggregated/
        // computed references на base data, canonical их стрипает; titleDataPath
        // canonical СОХРАНЯЕТ (identity, не computed). Эмпирически проверено на
        // ЗаказПоставщику/ФормаДокумента и ФормаСписка из ЕСС.
        java.util.Set<String> recursive = java.util.Set.of(
                "dataPath", "handlers", "commandName", "cmiFragmentRecord",
                "footerDataPath", "rowPictureDataPath");
        stripRecursive(root, recursive);
    }

    private static void stripRecursive(Node node, java.util.Set<String> tags) {
        java.util.List<Node> toRemove = new java.util.ArrayList<>();
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                if (tags.contains(k.getNodeName())) {
                    toRemove.add(k);
                } else {
                    stripRecursive(k, tags);
                }
            }
        }
        for (Node n : toRemove) node.removeChild(n);
    }

    private static String findBaseFormUuid(Document doc, String formName) {
        NodeList list = doc.getElementsByTagName("forms");
        for (int i = 0; i < list.getLength(); i++) {
            Element f = (Element) list.item(i);
            if (formName.equals(childText(f, "name"))) {
                return f.getAttribute("uuid");
            }
        }
        return null;
    }

    private static Element findFormElementByName(Element root, String formName) {
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && "forms".equals(k.getNodeName())
                    && formName.equals(childText((Element) k, "name"))) {
                return (Element) k;
            }
        }
        return null;
    }

    // --- shared XML helpers (copy from MdObjectBorrower) ---

    private static Document parseXml(IFile file) throws ToolException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(file.getContents());
            stripWhitespace(doc.getDocumentElement());
            return doc;
        } catch (CoreException | ParserConfigurationException | SAXException | IOException e) {
            throw new ToolException("failed to parse " + file.getFullPath() + ": " + e.getMessage());
        }
    }

    private static void writeXml(IFile file, Document doc) throws ToolException {
        stripWhitespace(doc.getDocumentElement());
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(baos));
            file.setContents(new ByteArrayInputStream(baos.toByteArray()), true, true, new NullProgressMonitor());
        } catch (TransformerException | CoreException e) {
            throw new ToolException("failed to write " + file.getFullPath() + ": " + e.getMessage());
        }
    }

    private static void stripWhitespace(Node node) {
        NodeList kids = node.getChildNodes();
        for (int i = kids.getLength() - 1; i >= 0; i--) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.TEXT_NODE && k.getTextContent().isBlank()) {
                node.removeChild(k);
            } else if (k.getNodeType() == Node.ELEMENT_NODE) {
                stripWhitespace(k);
            }
        }
    }

    private static void appendText(Document doc, Element parent, String tag, String text) {
        Element e = doc.createElement(tag);
        e.setTextContent(text);
        parent.appendChild(e);
    }

    private static String childText(Element parent, String tag) {
        NodeList n = parent.getChildNodes();
        for (int i = 0; i < n.getLength(); i++) {
            Node node = n.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())
                    && node.getParentNode() == parent) {
                return node.getTextContent();
            }
        }
        return null;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
