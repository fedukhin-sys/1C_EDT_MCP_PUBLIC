package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Stage 8d — заимствование (adopt) MdObject'а из base configuration в extension.
 *
 * <p>Шаги:
 * <ol>
 *   <li>Resolve parent project через {@link IExtensionProject#getParent()}.</li>
 *   <li>Прочитать base {@code .mdo}, извлечь {@code uuid} + {@code <producedTypes>}.</li>
 *   <li>Сгенерировать adopted {@code .mdo}:
 *     <ul>
 *       <li>{@code uuid="<NEW>"}, {@code extendedConfigurationObject="<BASE_UUID>"}</li>
 *       <li>{@code <producedTypes>} — те же tag'и, но все {@code typeId}/{@code valueTypeId} заново random.</li>
 *       <li>{@code <name>X</name>}</li>
 *       <li>{@code <objectBelonging>Adopted</objectBelonging>}</li>
 *       <li>{@code <extension xsi:type="mdclassExtension:<Kind>Extension">
 *           <extendedConfigurationObject>Checked</extendedConfigurationObject>
 *           </extension>}</li>
 *     </ul>
 *   </li>
 *   <li>Добавить ссылку в extension's {@code Configuration.mdo} (e.g. {@code <catalogs>Catalog.X</catalogs>}).</li>
 *   <li>refreshLocal + {@link IBmModelManager#waitModelSynchronization} для BM sync.</li>
 * </ol>
 *
 * <p>Sub-borrow (Form/TabularSection/EnumValue) — отдельная задача. Этот tool
 * только адоптирует top-level объект; sub-elements остаются «как в base» —
 * extension может дальше редактировать их через специальные tools.
 */
@Singleton
public class MdObjectBorrower {

    private static final String MDCLASS_NS     = "http://g5.1c.ru/v8/dt/metadata/mdclass";
    private static final String MDCLASS_EXT_NS = "http://g5.1c.ru/v8/dt/metadata/mdclass/extension";
    private static final String XSI_NS         = "http://www.w3.org/2001/XMLSchema-instance";

    /**
     * Метаданные для одного kind: имя папки в src/, имя plural-property в Configuration.mdo,
     * имя extension-типа.
     */
    public record KindMeta(String folder, String pluralProp, String extType) { }

    /** Поддерживаемые kinds. */
    private static final Map<String, KindMeta> KINDS = new LinkedHashMap<>();
    static {
        // С producedTypes
        KINDS.put("Catalog",                    new KindMeta("Catalogs",                    "catalogs",                    "CatalogExtension"));
        KINDS.put("Document",                   new KindMeta("Documents",                   "documents",                   "DocumentExtension"));
        KINDS.put("Enum",                       new KindMeta("Enums",                       "enums",                       "EnumExtension"));
        KINDS.put("InformationRegister",        new KindMeta("InformationRegisters",        "informationRegisters",        "InformationRegisterExtension"));
        KINDS.put("AccumulationRegister",       new KindMeta("AccumulationRegisters",       "accumulationRegisters",       "AccumulationRegisterExtension"));
        KINDS.put("DataProcessor",              new KindMeta("DataProcessors",              "dataProcessors",              "DataProcessorExtension"));
        KINDS.put("Report",                     new KindMeta("Reports",                     "reports",                     "ReportExtension"));
        KINDS.put("BusinessProcess",            new KindMeta("BusinessProcesses",           "businessProcesses",           "BusinessProcessExtension"));
        KINDS.put("Task",                       new KindMeta("Tasks",                       "tasks",                       "TaskExtension"));
        KINDS.put("ChartOfAccounts",            new KindMeta("ChartsOfAccounts",            "chartsOfAccounts",            "ChartOfAccountsExtension"));
        KINDS.put("ChartOfCalculationTypes",    new KindMeta("ChartsOfCalculationTypes",    "chartsOfCalculationTypes",    "ChartOfCalculationTypesExtension"));
        KINDS.put("ChartOfCharacteristicTypes", new KindMeta("ChartsOfCharacteristicTypes", "chartsOfCharacteristicTypes", "ChartOfCharacteristicTypesExtension"));
        KINDS.put("ExchangePlan",               new KindMeta("ExchangePlans",               "exchangePlans",               "ExchangePlanExtension"));
        KINDS.put("DocumentJournal",            new KindMeta("DocumentJournals",            "documentJournals",            "DocumentJournalExtension"));
        // Без producedTypes
        KINDS.put("CommonModule",               new KindMeta("CommonModules",               "commonModules",               "CommonModuleExtension"));
        KINDS.put("CommonForm",                 new KindMeta("CommonForms",                 "commonForms",                 "CommonFormExtension"));
        KINDS.put("CommonPicture",              new KindMeta("CommonPictures",              "commonPictures",              "CommonPictureExtension"));
        KINDS.put("Constant",                   new KindMeta("Constants",                   "constants",                   "ConstantExtension"));
        KINDS.put("Subsystem",                  new KindMeta("Subsystems",                  "subsystems",                  "SubsystemExtension"));
    }

    public record BorrowResult(String project, String fqn, String adoptedUuid,
                                String baseUuid, String mdoPath,
                                List<String> cascadedOwners) {
        public BorrowResult(String project, String fqn, String adoptedUuid,
                            String baseUuid, String mdoPath) {
            this(project, fqn, adoptedUuid, baseUuid, mdoPath, List.of());
        }
    }

    private final IV8ProjectManager projectManager;
    private final IBmModelManager   bmModelManager;

    @Inject
    public MdObjectBorrower(IV8ProjectManager projectManager, IBmModelManager bmModelManager) {
        this.projectManager = projectManager;
        this.bmModelManager = bmModelManager;
    }

    public BorrowResult borrow(IProject extIProject, String fqn) throws ToolException {
        return borrow(extIProject, fqn, new HashSet<>());
    }

    /**
     * Внутренний рекурсивный borrow с visited-set для каскадного обхода owner-цепочек
     * (Bug A1, 2026-05-31). Catalog с {@code <owners>} требует, чтобы хотя бы один owner
     * был тоже adopted в extension'е, иначе deploy валится «нельзя добавлять без загрузки
     * родительского». Cascade: при borrow X — auto-borrow всех owners X, которых ещё нет.
     */
    private BorrowResult borrow(IProject extIProject, String fqn, Set<String> visited)
            throws ToolException {
        // Parse fqn
        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot == fqn.length() - 1) {
            throw new ToolException("fqn must be 'Kind.Name', got: '" + fqn + "'");
        }
        String kind = fqn.substring(0, dot);
        String name = fqn.substring(dot + 1);
        KindMeta meta = KINDS.get(kind);
        if (meta == null) {
            throw new ToolException("kind '" + kind + "' is not supported for borrow; supported: " + KINDS.keySet());
        }

        // Resolve extension + base
        IV8Project extV8 = projectManager.getProject(extIProject);
        if (!(extV8 instanceof IExtensionProject extProject)) {
            throw new ToolException("project '" + extIProject.getName()
                    + "' is not an Extension project — borrow_md_object works only for extensions");
        }
        IConfigurationProject baseProject = extProject.getParent();
        if (baseProject == null) {
            throw new ToolException("extension '" + extIProject.getName() + "' has no parent configuration");
        }
        IProject baseIProject = baseProject.getProject();

        // Already borrowed?
        IFile adoptedFile = extIProject.getFile("src/" + meta.folder + "/" + name + "/" + name + ".mdo");
        if (adoptedFile.exists()) {
            throw new ToolException(kind + "." + name + " already borrowed in extension '"
                    + extIProject.getName() + "'");
        }

        // Read base .mdo
        IFile baseFile = baseIProject.getFile("src/" + meta.folder + "/" + name + "/" + name + ".mdo");
        if (!baseFile.exists()) {
            throw new ToolException(kind + "." + name + " not found in base configuration '"
                    + baseIProject.getName() + "' at " + baseFile.getFullPath());
        }
        Document baseDoc = parseXml(baseFile);
        Element baseRoot = baseDoc.getDocumentElement();
        String baseUuid = baseRoot.getAttribute("uuid");
        if (baseUuid == null || baseUuid.isEmpty()) {
            throw new ToolException("base " + kind + "." + name + " has no 'uuid' attribute on root");
        }

        // A1 fix (cascade owners): для Catalog читаем <owners> из base. Для каждого
        // owner-fqn, который НЕ заимствован в extension'е и НЕ адаптирован уже —
        // borrow его рекурсивно (visited-set против циклов). Без этого Catalog с
        // owner-родителем валится на deploy «нельзя добавлять без загрузки родительского».
        List<String> ownersFqns = ("Catalog".equals(kind)) ? readOwners(baseRoot) : List.of();
        List<String> cascaded = new ArrayList<>();
        visited.add(fqn);
        for (String ownerFqn : ownersFqns) {
            if (visited.contains(ownerFqn)) continue;
            // Owner FQN format: "Catalog.X"
            int odot = ownerFqn.indexOf('.');
            if (odot <= 0) continue;
            String oName = ownerFqn.substring(odot + 1);
            IFile ownerAdopted = extIProject.getFile("src/Catalogs/" + oName + "/" + oName + ".mdo");
            if (ownerAdopted.exists()) continue; // уже borrowed
            try {
                borrow(extIProject, ownerFqn, visited);
                cascaded.add(ownerFqn);
            } catch (ToolException e) {
                throw new ToolException("cascade borrow of owner '" + ownerFqn + "' failed: "
                        + e.getMessage());
            }
        }

        // Build adopted doc
        String adoptedUuid = UUID.randomUUID().toString();
        Document adoptedDoc = buildAdoptedDoc(baseRoot, kind, name, adoptedUuid, baseUuid, meta,
                ownersFqns);

        // Write adopted .mdo
        try {
            ensureFolder((IFolder) adoptedFile.getParent());
        } catch (CoreException e) {
            throw new ToolException("failed to create folder for " + adoptedFile.getFullPath()
                    + ": " + e.getMessage());
        }
        writeXml(adoptedFile, adoptedDoc);

        // CommonForm: kind содержит inline-nested Form.form + Module.bsl, которые
        // .mdo skeleton не передаёт. Без них редактор формы зависает и Enterprise
        // показывает пустую форму. Эталон полного borrow — Юпитер extensions:
        // 4 файла (.mdo с <form>Extended</form>, Form.form, BaseForm/Form.form, Module.bsl).
        if ("CommonForm".equals(kind)) {
            copyCommonFormFiles(baseIProject, extIProject, name);
        }

        // Update extension Configuration.mdo
        addToExtConfiguration(extIProject, meta.pluralProp, kind + "." + name);

        // BM sync
        try {
            extIProject.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        } catch (CoreException ignored) { /* best-effort */ }
        try {
            bmModelManager.waitModelSynchronization(extIProject);
        } catch (Throwable ignored) { /* best-effort */ }

        return new BorrowResult(extIProject.getName(), fqn, adoptedUuid, baseUuid,
                "src/" + meta.folder + "/" + name + "/" + name + ".mdo",
                Collections.unmodifiableList(cascaded));
    }

    /** Читает {@code <owners>Catalog.X</owners>} child-элементы корня base catalog .mdo. */
    private static List<String> readOwners(Element baseRoot) {
        List<String> out = new ArrayList<>();
        NodeList kids = baseRoot.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE) continue;
            if (!"owners".equals(k.getNodeName())) continue;
            if (k.getParentNode() != baseRoot) continue;
            String txt = k.getTextContent();
            if (txt != null && !txt.isBlank()) out.add(txt.trim());
        }
        return out;
    }

    // -------------------------------------------------------------------------

    private static Document buildAdoptedDoc(Element baseRoot, String kind, String name,
                                            String adoptedUuid, String baseUuid,
                                            KindMeta meta, List<String> ownersFqns)
            throws ToolException {
        Document doc;
        try {
            doc = newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new ToolException("failed to create XML doc: " + e.getMessage());
        }
        Element root = doc.createElementNS(MDCLASS_NS, "mdclass:" + kind);
        root.setAttribute("xmlns:xsi", XSI_NS);
        root.setAttribute("xmlns:mdclass", MDCLASS_NS);
        root.setAttribute("xmlns:mdclassExtension", MDCLASS_EXT_NS);
        root.setAttribute("uuid", adoptedUuid);
        root.setAttribute("extendedConfigurationObject", baseUuid);
        doc.appendChild(root);

        // producedTypes — те же tag'и, но новые UUIDs
        Element basePT = firstChildByName(baseRoot, "producedTypes");
        if (basePT != null) {
            Element pt = doc.createElement("producedTypes");
            NodeList kids = basePT.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node k = kids.item(i);
                if (k.getNodeType() != Node.ELEMENT_NODE) continue;
                Element copy = doc.createElement(k.getNodeName());
                copy.setAttribute("typeId", UUID.randomUUID().toString());
                copy.setAttribute("valueTypeId", UUID.randomUUID().toString());
                pt.appendChild(copy);
            }
            root.appendChild(pt);
        }

        appendText(doc, root, "name", name);
        appendText(doc, root, "objectBelonging", "Adopted");

        // A1 fix: Catalog с <owners> — копируем owner-ссылки из base в adopted.
        // Документирует ownership-связь и помогает платформе резолвить «нельзя
        // добавлять без загрузки родительского». Cascade-borrow гарантирует, что
        // owner-catalog тоже adopted в этом extension'е.
        if (ownersFqns != null && !ownersFqns.isEmpty()) {
            for (String owner : ownersFqns) {
                appendText(doc, root, "owners", owner);
            }
        }

        Element ext = doc.createElement("extension");
        ext.setAttributeNS(XSI_NS, "xsi:type", "mdclassExtension:" + meta.extType);
        appendText(doc, ext, "extendedConfigurationObject", "Checked");
        // CommonForm — обязательный <form>Extended</form> в extension block,
        // без него EDT не понимает что форма расширяется (skeleton-only режим
        // ломает редактор и runtime).
        if ("CommonForm".equals(kind)) {
            appendText(doc, ext, "form", "Extended");
        }
        root.appendChild(ext);

        return doc;
    }

    /**
     * Копирует inline-nested файлы CommonForm из base в extension:
     * <ul>
     *   <li>{@code Form.form} → {@code ext/Form.form} (1:1)</li>
     *   <li>{@code Form.form} → {@code ext/BaseForm/Form.form} (baseline для EDT diff)</li>
     *   <li>пустой {@code ext/Module.bsl} (extension Module = только override)</li>
     * </ul>
     *
     * <p>Если base не имеет {@code Form.form} — копирование пропускается; .mdo
     * с {@code <form>Extended</form>} остаётся валидным (EDT покажет пустой layout).
     */
    private static void copyCommonFormFiles(IProject baseProject, IProject extProject, String name)
            throws ToolException {
        IFile baseFormFile = baseProject.getFile("src/CommonForms/" + name + "/Form.form");
        if (baseFormFile.exists()) {
            IFile extFormFile     = extProject.getFile("src/CommonForms/" + name + "/Form.form");
            IFile extBaseFormFile = extProject.getFile("src/CommonForms/" + name + "/BaseForm/Form.form");
            try {
                ensureFolder((IFolder) extFormFile.getParent());
                ensureFolder((IFolder) extBaseFormFile.getParent());
            } catch (CoreException e) {
                throw new ToolException("failed to create CommonForm folders: " + e.getMessage());
            }
            copyFileContents(baseFormFile, extFormFile);
            copyFileContents(baseFormFile, extBaseFormFile);
        }
        IFile extModule = extProject.getFile("src/CommonForms/" + name + "/Module.bsl");
        if (!extModule.exists()) {
            try {
                ensureFolder((IFolder) extModule.getParent());
                extModule.create(new ByteArrayInputStream(new byte[0]), true, new NullProgressMonitor());
            } catch (CoreException e) {
                throw new ToolException("failed to create empty Module.bsl: " + e.getMessage());
            }
        }
    }

    private static void copyFileContents(IFile src, IFile dst) throws ToolException {
        try {
            if (dst.exists()) {
                dst.setContents(src.getContents(), true, true, new NullProgressMonitor());
            } else {
                dst.create(src.getContents(), true, new NullProgressMonitor());
            }
        } catch (CoreException e) {
            throw new ToolException("failed to copy " + src.getFullPath() + " → "
                    + dst.getFullPath() + ": " + e.getMessage());
        }
    }

    /**
     * Добавить {@code <pluralProp>Kind.Name</pluralProp>} в extension Configuration.mdo.
     * Якорь: после последнего существующего {@code <pluralProp>} элемента (если есть),
     * иначе append at end of root.
     */
    private static void addToExtConfiguration(IProject extProject, String pluralProp, String fqn)
            throws ToolException {
        IFile cfgFile = extProject.getFile("src/Configuration/Configuration.mdo");
        if (!cfgFile.exists()) {
            throw new ToolException("extension Configuration.mdo not found at " + cfgFile.getFullPath());
        }
        Document doc = parseXml(cfgFile);
        Element root = doc.getDocumentElement();

        // Dup check
        NodeList existing = root.getElementsByTagName(pluralProp);
        for (int i = 0; i < existing.getLength(); i++) {
            if (fqn.equals(existing.item(i).getTextContent())) {
                return; // idempotent — already listed
            }
        }

        Element entry = doc.createElement(pluralProp);
        entry.setTextContent(fqn);

        // Insert after last existing <pluralProp> element if any; else append at end
        Node anchor = null;
        if (existing.getLength() > 0) {
            anchor = existing.item(existing.getLength() - 1).getNextSibling();
        }
        if (anchor != null) root.insertBefore(entry, anchor);
        else                root.appendChild(entry);

        writeXml(cfgFile, doc);
    }

    // --- XML helpers ---

    private static Document parseXml(IFile file) throws ToolException {
        try {
            return newDocumentBuilder().parse(file.getContents());
        } catch (CoreException | ParserConfigurationException | SAXException | IOException e) {
            throw new ToolException("failed to parse " + file.getFullPath() + ": " + e.getMessage());
        }
    }

    private static void writeXml(IFile file, Document doc) throws ToolException {
        // Strip whitespace-only text nodes для чистой re-serialization
        stripWhitespace(doc.getDocumentElement());
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(baos));
            byte[] bytes = baos.toByteArray();
            if (file.exists()) {
                file.setContents(new ByteArrayInputStream(bytes), true, true, new NullProgressMonitor());
            } else {
                file.create(new ByteArrayInputStream(bytes), true, new NullProgressMonitor());
            }
        } catch (TransformerException | CoreException e) {
            throw new ToolException("failed to write " + file.getFullPath() + ": " + e.getMessage());
        }
    }

    private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder();
    }

    private static void appendText(Document doc, Element parent, String tag, String text) {
        Element e = doc.createElement(tag);
        e.setTextContent(text);
        parent.appendChild(e);
    }

    private static Element firstChildByName(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) {
                return (Element) k;
            }
        }
        return null;
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

    private static void ensureFolder(IFolder folder) throws CoreException {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) ensureFolder(parent);
        folder.create(/*force*/ false, /*local*/ true, new NullProgressMonitor());
    }
}
