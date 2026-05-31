package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * DOM-based editor для <code>&lt;Owner&gt;.mdo</code> файлов (Catalog/Document/etc).
 * Поддерживает Stage 8e операции: добавление TabularSection + колонок к нему.
 *
 * <p>Параллель {@code FormFileEditor} для form-XML — но работает с parent-MdObject .mdo.
 * Использует raw DOM (не EMF), чтобы не зависеть от BM-транзакции.
 */
@Singleton
public class MdoFileEditor {

    /** Spec для add_tabular_section. */
    public record TabularSectionSpec(String name) { }

    /** Spec для добавления column в TabularSection. */
    public record TsAttributeSpec(String tsName, String name, String type) { }

    /** Spec для добавления root-level &lt;attributes&gt; в parent .mdo (Catalog/Document/etc). */
    public record MdAttributeSpec(String name, String type) { }

    /**
     * Spec для добавления Attribute/Dimension/Resource в Register .mdo
     * (AccumulationRegister/InformationRegister).
     *
     * <p>{@code types} — список type-выражений (для multi-type Dimension AccReg типично
     * передавать несколько DocumentRef.X — каждый записывается отдельным
     * {@code <types>X</types>} элементом). Qualifiers (numberQualifiers/stringQualifiers/
     * dateQualifiers) пишутся ТОЛЬКО для первого типа списка, как в каноническом .mdo.
     */
    public record RegisterAttrSpec(String role, String name, List<String> types) {
        public RegisterAttrSpec {
            if (role == null
                    || !(role.equals("Attribute") || role.equals("Dimension") || role.equals("Resource"))) {
                throw new IllegalArgumentException("role must be Attribute|Dimension|Resource, got: " + role);
            }
            if (types == null || types.isEmpty()) {
                throw new IllegalArgumentException("types must be non-empty");
            }
        }
    }

    private final IBmModelManager bmModelManager;

    @Inject
    public MdoFileEditor(IBmModelManager bmModelManager) {
        this.bmModelManager = bmModelManager;
    }

    /** Test seam. */
    public MdoFileEditor() { this(null); }

    /**
     * Добавить inline {@code <tabularSections>} block в parent .mdo.
     * Anchor: вставляется перед {@code <forms>} / {@code <commands>} / {@code <commandInterface>},
     * иначе append at end.
     */
    public void addTabularSection(IFile mdoFile, TabularSectionSpec spec) throws ToolException {
        Document doc = load(mdoFile);
        Element root = doc.getDocumentElement();

        // Dup check
        NodeList existing = root.getElementsByTagName("tabularSections");
        for (int i = 0; i < existing.getLength(); i++) {
            Element ts = (Element) existing.item(i);
            if (ts.getParentNode() != root) continue;
            if (spec.name.equals(childText(ts, "name"))) {
                throw new ToolException("tabular section '" + spec.name + "' already exists");
            }
        }

        Element ts = doc.createElement("tabularSections");
        ts.setAttribute("uuid", UUID.randomUUID().toString());

        // producedTypes: objectType + rowType (для TabularSection)
        Element pt = doc.createElement("producedTypes");
        Element objType = doc.createElement("objectType");
        objType.setAttribute("typeId",      UUID.randomUUID().toString());
        objType.setAttribute("valueTypeId", UUID.randomUUID().toString());
        pt.appendChild(objType);
        Element rowType = doc.createElement("rowType");
        rowType.setAttribute("typeId",      UUID.randomUUID().toString());
        rowType.setAttribute("valueTypeId", UUID.randomUUID().toString());
        pt.appendChild(rowType);
        ts.appendChild(pt);

        appendText(doc, ts, "name", spec.name);

        // Anchor
        Node anchor = firstChildByName(root, "forms");
        if (anchor == null) anchor = firstChildByName(root, "commands");
        if (anchor == null) anchor = firstChildByName(root, "commandInterface");
        if (anchor == null) anchor = firstChildByName(root, "extInfo");
        if (anchor != null) root.insertBefore(ts, anchor);
        else                root.appendChild(ts);

        save(mdoFile, doc);
    }

    /**
     * Добавить inline {@code <attributes>} (column) в существующую {@code <tabularSections>}.
     * type-выражения те же что в add_attribute (String(N), Number(P,S), CatalogRef.X, ...).
     */
    public void addTabularSectionAttribute(IFile mdoFile, TsAttributeSpec spec) throws ToolException {
        Document doc = load(mdoFile);
        Element root = doc.getDocumentElement();

        Element ts = findTabularSection(root, spec.tsName);
        if (ts == null) {
            throw new ToolException("tabular section '" + spec.tsName + "' not found in " + mdoFile.getName());
        }

        // Dup check на колонки в этой TS
        NodeList attrs = ts.getElementsByTagName("attributes");
        for (int i = 0; i < attrs.getLength(); i++) {
            Element a = (Element) attrs.item(i);
            if (a.getParentNode() != ts) continue;
            if (spec.name.equals(childText(a, "name"))) {
                throw new ToolException("column '" + spec.name + "' already exists in '" + spec.tsName + "'");
            }
        }

        Element attr = doc.createElement("attributes");
        attr.setAttribute("uuid", UUID.randomUUID().toString());
        attr.appendChild(buildType(doc, spec.type));
        appendText(doc, attr, "name", spec.name);

        ts.appendChild(attr);
        save(mdoFile, doc);
    }

    /**
     * Добавить root-level {@code <attributes>} block в parent .mdo
     * (Catalog/Document/BusinessProcess/Task/ChartOfX).
     *
     * <p>2026-05-18 fix: AttributeFactory (BM-based) генерировал {@code <type>}
     * без {@code <types>X</types>} элемента из-за «отложенной» wiring
     * IEObjectTypeContainer (комментарий в коде). EDT-валидатор затем
     * жаловался «Тип не установлен». DOM-route обходит проблему: пишем XML
     * вручную с правильным {@code <types>X</types>}, как делает каноник.
     */
    public void addMdObjectAttribute(IFile mdoFile, MdAttributeSpec spec) throws ToolException {
        Document doc = load(mdoFile);
        Element root = doc.getDocumentElement();

        // Dup check на root-level <attributes>
        NodeList existing = root.getElementsByTagName("attributes");
        for (int i = 0; i < existing.getLength(); i++) {
            Element a = (Element) existing.item(i);
            if (a.getParentNode() != root) continue;
            if (spec.name.equals(childText(a, "name"))) {
                throw new ToolException("attribute '" + spec.name + "' already exists on " + mdoFile.getName());
            }
        }

        Element attr = doc.createElement("attributes");
        attr.setAttribute("uuid", UUID.randomUUID().toString());
        attr.appendChild(buildType(doc, spec.type));
        appendText(doc, attr, "name", spec.name);

        // Anchor: insert before tabularSections/forms/commands/commandInterface/extInfo
        Node anchor = firstChildByName(root, "tabularSections");
        if (anchor == null) anchor = firstChildByName(root, "forms");
        if (anchor == null) anchor = firstChildByName(root, "commands");
        if (anchor == null) anchor = firstChildByName(root, "commandInterface");
        if (anchor == null) anchor = firstChildByName(root, "extInfo");
        if (anchor != null) root.insertBefore(attr, anchor);
        else                root.appendChild(attr);

        save(mdoFile, doc);
    }

    /**
     * Добавить inline {@code <attributes>} / {@code <dimensions>} / {@code <resources>}
     * в Register .mdo (AccumulationRegister/InformationRegister).
     *
     * <p>2026-05-18 v1.10.2 fix (Bug #2): AttributeFactory (BM-based) для Register kinds
     * писал пустой {@code <type/>} для REF-типов на Dimension (DocumentRef.X). EDT-валидатор
     * формально не флагит «Тип не установлен», но Dimension de-facto бесполезен — нельзя
     * записать в него движения. DOM-route обходит проблему: пишем XML вручную с правильным
     * {@code <types>DocumentRef.X</types>}, как делает каноник.
     *
     * <p>Для Dimension с несколькими типами (DocumentRef.X|DocumentRef.Y) в одной строке —
     * вставляем несколько {@code <types>} элементов.
     */
    public void addRegisterAttribute(IFile mdoFile, RegisterAttrSpec spec) throws ToolException {
        Document doc = load(mdoFile);
        Element root = doc.getDocumentElement();

        String tag = switch (spec.role) {
            case "Attribute" -> "attributes";
            case "Dimension" -> "dimensions";
            case "Resource"  -> "resources";
            default -> throw new ToolException("unexpected role: " + spec.role);
        };

        // Dup check на root-level элементы с таким же tag и именем.
        NodeList existing = root.getElementsByTagName(tag);
        for (int i = 0; i < existing.getLength(); i++) {
            Element a = (Element) existing.item(i);
            if (a.getParentNode() != root) continue;
            if (spec.name.equals(childText(a, "name"))) {
                throw new ToolException(spec.role + " '" + spec.name + "' already exists on "
                        + mdoFile.getName());
            }
        }

        Element attr = doc.createElement(tag);
        attr.setAttribute("uuid", UUID.randomUUID().toString());
        attr.appendChild(buildMultiType(doc, spec.types));
        appendText(doc, attr, "name", spec.name);

        // Регистровая .mdo обычно завершается dimensions/resources blocks; вставляем
        // перед extInfo если есть, иначе перед recordSetExtInfo, иначе append at end.
        Node anchor = firstChildByName(root, "extInfo");
        if (anchor == null) anchor = firstChildByName(root, "recordSetExtInfo");
        if (anchor != null) root.insertBefore(attr, anchor);
        else                root.appendChild(attr);

        save(mdoFile, doc);
    }

    /**
     * Добавить {@code <recorders>Document.X</recorders>} в AccumulationRegister .mdo
     * (или AccountingRegister/CalculationRegister в будущем). Идемпотентно: если такая
     * recorder-строка уже есть — no-op.
     *
     * <p>Bug #1 fix: AccReg без хотя бы одного recorder'а валится на deploy с «Некорректный
     * состав регистраторов». Phase B приходилось править .mdo руками.
     *
     * @param mdoFile     .mdo регистра
     * @param documentFqn fqn документа вида {@code "Document.X"}
     */
    public boolean addRecorder(IFile mdoFile, String documentFqn) throws ToolException {
        if (documentFqn == null || documentFqn.isEmpty()) {
            throw new ToolException("document fqn is required");
        }
        Document doc = load(mdoFile);
        Element root = doc.getDocumentElement();

        // Dup check
        NodeList existing = root.getElementsByTagName("recorders");
        for (int i = 0; i < existing.getLength(); i++) {
            Element r = (Element) existing.item(i);
            if (r.getParentNode() != root) continue;
            if (documentFqn.equals(r.getTextContent())) {
                return false; // already present — no-op
            }
        }

        Element rec = doc.createElement("recorders");
        rec.setTextContent(documentFqn);

        // Анчор: после <registerType>/<dataLockControlMode>/<synonym>/<name>, перед
        // <dimensions>/<resources>/<attributes>. В каноне Phase B: после <dataLockControlMode>,
        // перед <registerType>. Используем перед первым из dimensions/resources/attributes/extInfo.
        Node anchor = firstChildByName(root, "dimensions");
        if (anchor == null) anchor = firstChildByName(root, "resources");
        if (anchor == null) anchor = firstChildByName(root, "attributes");
        if (anchor == null) anchor = firstChildByName(root, "extInfo");
        if (anchor == null) anchor = firstChildByName(root, "registerType");
        if (anchor != null) root.insertBefore(rec, anchor);
        else                root.appendChild(rec);

        save(mdoFile, doc);
        return true;
    }

    /**
     * Locator для целевого Attribute/Dimension/Resource в parent .mdo.
     *
     * @param tag     XML-тэг: {@code attributes} / {@code dimensions} / {@code resources}
     * @param tsName  если ≠ null — Attribute является колонкой TabularSection с таким
     *                именем (только {@code tag="attributes"} имеет смысл с {@code tsName!=null})
     * @param name    имя самого Attribute
     */
    public record AttrLocator(String tag, String tsName, String name) {
        public AttrLocator {
            if (tag == null || name == null || name.isEmpty()) {
                throw new IllegalArgumentException("tag/name required");
            }
            if (!(tag.equals("attributes") || tag.equals("dimensions") || tag.equals("resources"))) {
                throw new IllegalArgumentException(
                        "tag must be attributes|dimensions|resources, got: " + tag);
            }
            if (tsName != null && !tag.equals("attributes")) {
                throw new IllegalArgumentException(
                        "TabularSection sub-attribute can only have tag=attributes");
            }
        }
    }

    /**
     * Установить {@code <type>} у произвольного Attribute/Dimension/Resource в parent
     * .mdo (Catalog/Document/Register/etc), replace-or-add семантика.
     *
     * <p>Поддерживает:
     * <ul>
     *   <li>root-level Attribute (Catalog/Document/BusinessProcess/Task) — {@code AttrLocator("attributes", null, name)};</li>
     *   <li>TabularSection-column (Catalog/Document) — {@code AttrLocator("attributes", tsName, name)};</li>
     *   <li>Register Dimension/Resource/Attribute (AccReg/InfReg) — {@code AttrLocator("dimensions"|"resources"|"attributes", null, name)}.</li>
     * </ul>
     *
     * <p>Если attribute с таким именем не найден — {@link ToolException} (используй
     * {@code add_attribute} / {@code add_tabular_section_attribute} для создания).
     *
     * @return {@code true} если {@code <type>} был перезаписан, {@code false} если добавлен впервые.
     */
    public boolean setAttributeType(IFile mdoFile, AttrLocator locator, List<String> types)
            throws ToolException {
        if (types == null || types.isEmpty()) {
            throw new ToolException("types must be non-empty");
        }
        Document doc = load(mdoFile);
        boolean replaced = applySetAttributeTypeToDoc(doc, locator, types);
        save(mdoFile, doc);
        return replaced;
    }

    /**
     * Pure-DOM helper для {@link #setAttributeType}. Тестируется без живого IFile.
     *
     * @return {@code true} если был old {@code <type>}, который мы перезаписали;
     *         {@code false} если не было (просто добавили).
     * @throws ToolException если attribute не найден в .mdo.
     */
    public static boolean applySetAttributeTypeToDoc(Document doc, AttrLocator locator,
                                                     List<String> types) throws ToolException {
        Element root = doc.getDocumentElement();
        if (root == null) throw new ToolException("empty .mdo document");

        Element scope = root;
        if (locator.tsName != null) {
            Element ts = findTabularSection(root, locator.tsName);
            if (ts == null) {
                throw new ToolException("tabular section '" + locator.tsName + "' not found");
            }
            scope = ts;
        }

        Element attr = findChildNamed(scope, locator.tag, locator.name);
        if (attr == null) {
            String where = (locator.tsName == null)
                    ? "root .mdo (" + locator.tag + ")"
                    : "tabularSection '" + locator.tsName + "'";
            throw new ToolException(locator.tag + " '" + locator.name + "' not found in " + where);
        }

        boolean hadType = false;
        NodeList typeKids = attr.getElementsByTagName("type");
        for (int i = typeKids.getLength() - 1; i >= 0; i--) {
            Element t = (Element) typeKids.item(i);
            if (t.getParentNode() == attr) {
                attr.removeChild(t);
                hadType = true;
            }
        }

        Element newType = buildMultiType(doc, types);
        // В каноне порядок дочерних элементов: producedTypes (если есть),
        // toolTip/synonym, <type>, <name>. <name> идёт ПОСЛЕ <type> в .mdo каноне.
        // Если есть существующий <name>, ставим перед ним; иначе перед первым
        // не-producedTypes child; иначе append.
        Node anchor = firstChildByName(attr, "name");
        if (anchor == null) {
            NodeList kids = attr.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node k = kids.item(i);
                if (k.getNodeType() == Node.ELEMENT_NODE && !"producedTypes".equals(k.getNodeName())) {
                    anchor = k;
                    break;
                }
            }
        }
        if (anchor != null) attr.insertBefore(newType, anchor);
        else                attr.appendChild(newType);
        return hadType;
    }

    private static Element findChildNamed(Element parent, String tag, String name) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node k = list.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE || !tag.equals(k.getNodeName())) continue;
            Element e = (Element) k;
            if (name.equals(childText(e, "name"))) return e;
        }
        return null;
    }

    /**
     * Установить {@code <type>} в Constant .mdo. Replace-or-add: если блок уже есть,
     * перезаписываем; иначе вставляем перед {@code <dataLockControlMode>} или в конец.
     *
     * <p>Поддерживает multi-typed (для составных типов вроде «Число или Строка»):
     * каждый элемент списка пишется отдельным {@code <types>X</types>}; qualifiers —
     * только для первого типа в каноне.
     *
     * @param mdoFile .mdo константы (или MdObject с feature {@code type} как TypeDescription)
     * @param types   список type-выражений в формате {@code String(N)} / {@code Number(P,S)} /
     *                {@code Date} / {@code Boolean} / {@code CatalogRef.X} / {@code AnyRef} / {@code UUID}
     */
    public boolean setConstantType(IFile mdoFile, List<String> types) throws ToolException {
        if (types == null || types.isEmpty()) {
            throw new ToolException("types must be non-empty");
        }
        Document doc = load(mdoFile);
        Element root = doc.getDocumentElement();

        // Найти существующий root-level <type> и удалить
        NodeList existing = root.getElementsByTagName("type");
        for (int i = existing.getLength() - 1; i >= 0; i--) {
            Element t = (Element) existing.item(i);
            if (t.getParentNode() == root) {
                root.removeChild(t);
            }
        }

        Element typeEl = buildMultiType(doc, types);

        // Anchor: перед dataLockControlMode/extInfo, иначе перед первым «не верхним» feature.
        // Каноник Constant: producedTypes, name, synonym, type, dataLockControlMode.
        Node anchor = firstChildByName(root, "dataLockControlMode");
        if (anchor == null) anchor = firstChildByName(root, "extInfo");
        if (anchor != null) root.insertBefore(typeEl, anchor);
        else                root.appendChild(typeEl);

        save(mdoFile, doc);
        return true;
    }

    /**
     * Добавить {@code <content>Kind.Name</content>} в Subsystem .mdo.
     * Идемпотентно. Используется {@code add_subsystem_content} для биндинга объектов
     * расширения к подсистемам (command interface).
     *
     * @param mdoFile    .mdo подсистемы
     * @param contentFqn fqn объекта вида {@code "Catalog.X"} / {@code "Document.Y"} / etc.
     */
    public boolean addSubsystemContent(IFile mdoFile, String contentFqn) throws ToolException {
        if (contentFqn == null || contentFqn.isEmpty()) {
            throw new ToolException("content fqn is required");
        }
        Document doc = load(mdoFile);
        Element root = doc.getDocumentElement();

        NodeList existing = root.getElementsByTagName("content");
        for (int i = 0; i < existing.getLength(); i++) {
            Element c = (Element) existing.item(i);
            if (c.getParentNode() != root) continue;
            if (contentFqn.equals(c.getTextContent())) {
                return false; // already present — no-op
            }
        }

        Element ce = doc.createElement("content");
        ce.setTextContent(contentFqn);

        // Анчор: перед subsystems/explanation/extInfo, иначе в конец.
        Node anchor = firstChildByName(root, "subsystems");
        if (anchor == null) anchor = firstChildByName(root, "explanation");
        if (anchor == null) anchor = firstChildByName(root, "extInfo");
        if (anchor != null) root.insertBefore(ce, anchor);
        else                root.appendChild(ce);

        save(mdoFile, doc);
        return true;
    }

    /**
     * Добавить в Report .mdo блок {@code <templates>} для DCS-template и
     * {@code <mainDataCompositionSchema>Report.X.Template.NAME</mainDataCompositionSchema>}.
     * Идемпотентно: если template/main ссылка уже есть — соответствующая часть
     * пропускается. Возвращает {@code true} если хотя бы один из элементов был добавлен.
     */
    public boolean addReportDcsTemplate(IFile reportMdoFile, String reportName, String templateName)
            throws ToolException {
        if (templateName == null || templateName.isEmpty()) {
            throw new ToolException("templateName required");
        }
        Document doc = load(reportMdoFile);
        Element root = doc.getDocumentElement();
        boolean changed = false;

        // Добавить mainDataCompositionSchema если нет
        if (firstChildByName(root, "mainDataCompositionSchema") == null) {
            Element main = doc.createElement("mainDataCompositionSchema");
            main.setTextContent("Report." + reportName + ".Template." + templateName);
            // Anchor: перед templates, иначе перед forms/commands/extInfo, иначе append.
            Node anchor = firstChildByName(root, "templates");
            if (anchor == null) anchor = firstChildByName(root, "forms");
            if (anchor == null) anchor = firstChildByName(root, "commands");
            if (anchor == null) anchor = firstChildByName(root, "extInfo");
            if (anchor != null) root.insertBefore(main, anchor);
            else                root.appendChild(main);
            changed = true;
        }

        // Добавить <templates> entry с этим именем если ещё нет
        NodeList tps = root.getElementsByTagName("templates");
        boolean templateExists = false;
        for (int i = 0; i < tps.getLength(); i++) {
            Element t = (Element) tps.item(i);
            if (t.getParentNode() != root) continue;
            if (templateName.equals(childText(t, "name"))) {
                templateExists = true;
                break;
            }
        }
        if (!templateExists) {
            Element t = doc.createElement("templates");
            t.setAttribute("uuid", UUID.randomUUID().toString());
            appendText(doc, t, "name", templateName);
            appendText(doc, t, "templateType", "DataCompositionSchema");
            Node anchor = firstChildByName(root, "forms");
            if (anchor == null) anchor = firstChildByName(root, "commands");
            if (anchor == null) anchor = firstChildByName(root, "extInfo");
            if (anchor != null) root.insertBefore(t, anchor);
            else                root.appendChild(t);
            changed = true;
        }

        if (changed) save(reportMdoFile, doc);
        return changed;
    }

    /**
     * Дописать в InformationRegister .mdo явный {@code <writeMode>Independent</writeMode>},
     * если такого тега нет.
     *
     * <p>Why: {@code MdObjectFactory.applyKindDefaults} выставляет
     * {@code writeMode=Independent} в BM-модель, но EDT XML-serializer пропускает
     * значения, равные default'у enum'а. В результате в .mdo {@code <writeMode>}
     * вообще отсутствует, и при чтении .mdo каким-нибудь pre-flight инструментом
     * (вне EDT) регистр выглядит «безрежимным». Косметика, но раздражающая.
     *
     * <p>Идемпотентно: если {@code <writeMode>} уже есть — no-op.
     *
     * @return {@code true} если тег был добавлен, {@code false} если уже присутствовал
     *         (или корень не {@code informationRegister}).
     */
    public boolean ensureInfRegWriteMode(IFile mdoFile) throws ToolException {
        Document doc = load(mdoFile);
        boolean changed = applyInfRegWriteModeToDoc(doc);
        if (changed) save(mdoFile, doc);
        return changed;
    }

    /**
     * Pure-DOM helper для {@link #ensureInfRegWriteMode}. Тестируется без живого
     * IFile (см. {@code MdoFileEditorWriteModeTest}).
     */
    public static boolean applyInfRegWriteModeToDoc(Document doc) {
        Element root = doc.getDocumentElement();
        if (root == null) return false;
        String localName = root.getLocalName();
        if (localName == null) localName = root.getNodeName();
        // namespace prefix может быть mdclass:InformationRegister — берём local part
        int colon = localName.indexOf(':');
        if (colon >= 0) localName = localName.substring(colon + 1);
        if (!"InformationRegister".equals(localName)) return false;
        if (firstChildByName(root, "writeMode") != null) return false;

        Element wm = doc.createElement("writeMode");
        wm.setTextContent("Independent");

        // Канонический порядок Register .mdo: producedTypes, name, synonym, comment,
        // dataLockControlMode, writeMode, ..., dimensions, resources, attributes,
        // recordSetExtInfo, extInfo. Вставляем перед dimensions/resources/attributes/
        // recordSetExtInfo/extInfo если они есть, иначе append at end.
        Node anchor = firstChildByName(root, "dimensions");
        if (anchor == null) anchor = firstChildByName(root, "resources");
        if (anchor == null) anchor = firstChildByName(root, "attributes");
        if (anchor == null) anchor = firstChildByName(root, "recordSetExtInfo");
        if (anchor == null) anchor = firstChildByName(root, "extInfo");
        if (anchor != null) root.insertBefore(wm, anchor);
        else                root.appendChild(wm);
        return true;
    }

    /**
     * Добавить {@code <registerRecords>AccumulationRegister.X</registerRecords>} в Document .mdo.
     * Идемпотентно.
     *
     * <p>Bug #1 fix: вторая половина связи Document↔Register. Без этого блока документ
     * не виден EDT как regorder, и движения по регистру из ObjectModule.bsl не оформляются.
     *
     * @param mdoFile     .mdo документа
     * @param registerFqn fqn регистра вида {@code "AccumulationRegister.X"} / {@code "InformationRegister.Y"}
     */
    public boolean addRegisterRecord(IFile mdoFile, String registerFqn) throws ToolException {
        if (registerFqn == null || registerFqn.isEmpty()) {
            throw new ToolException("register fqn is required");
        }
        Document doc = load(mdoFile);
        Element root = doc.getDocumentElement();

        // Dup check
        NodeList existing = root.getElementsByTagName("registerRecords");
        for (int i = 0; i < existing.getLength(); i++) {
            Element r = (Element) existing.item(i);
            if (r.getParentNode() != root) continue;
            if (registerFqn.equals(r.getTextContent())) {
                return false; // already present — no-op
            }
        }

        Element rec = doc.createElement("registerRecords");
        rec.setTextContent(registerFqn);

        // Анчор: перед <attributes>/<tabularSections>/<forms>/<commands>/<commandInterface>/<extInfo>.
        // В каноне Phase B: после <unpostInPrivilegedMode>, перед <attributes>.
        Node anchor = firstChildByName(root, "attributes");
        if (anchor == null) anchor = firstChildByName(root, "tabularSections");
        if (anchor == null) anchor = firstChildByName(root, "forms");
        if (anchor == null) anchor = firstChildByName(root, "commands");
        if (anchor == null) anchor = firstChildByName(root, "commandInterface");
        if (anchor == null) anchor = firstChildByName(root, "extInfo");
        if (anchor != null) root.insertBefore(rec, anchor);
        else                root.appendChild(rec);

        save(mdoFile, doc);
        return true;
    }

    /**
     * BUG-16: reads attributes / dimensions / resources straight from the .mdo on
     * disk, so {@code list_attributes} never returns stale data after a DOM-route
     * mutation (the BM model lags behind the file by the last mutation).
     *
     * @param kind owner kind — registers also contribute dimensions and resources
     */
    public List<Map<String, Object>> readAttributes(IFile mdoFile, String kind) throws ToolException {
        Document doc = load(mdoFile);
        Element root = doc.getDocumentElement();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        readAttrTag(root, "attributes", "Attribute", out);
        if ("InformationRegister".equals(kind) || "AccumulationRegister".equals(kind)) {
            readAttrTag(root, "dimensions", "Dimension", out);
            readAttrTag(root, "resources",  "Resource",  out);
        }
        return out;
    }

    private static void readAttrTag(Element root, String tag, String role,
                                    List<Map<String, Object>> out) {
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE || !tag.equals(k.getNodeName())) {
                continue;
            }
            Element a = (Element) k;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            String name = childText(a, "name");
            m.put("name", name != null ? name : "");
            m.put("role", role);
            m.put("type", formatTypeElement(firstChildByName(a, "type")));
            m.put("synonym", synonymRu(a));
            String comment = childText(a, "comment");
            m.put("comment", comment != null ? comment : "");
            out.add(m);
        }
    }

    /** Formats an attribute {@code <type>} DOM element back to a type string (or list for composite). */
    private static Object formatTypeElement(Node typeNode) {
        if (!(typeNode instanceof Element typeEl)) {
            return "";
        }
        List<String> rawTypes = new java.util.ArrayList<>();
        Element stringQ = null;
        Element numberQ = null;
        NodeList kids = typeEl.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (k.getNodeName()) {
                case "types"            -> rawTypes.add(k.getTextContent().trim());
                case "stringQualifiers" -> stringQ = (Element) k;
                case "numberQualifiers" -> numberQ = (Element) k;
                default                 -> { /* dateQualifiers etc. — not needed for the short form */ }
            }
        }
        if (rawTypes.isEmpty()) {
            return "";
        }
        List<String> formatted = new java.util.ArrayList<>(rawTypes.size());
        for (String t : rawTypes) {
            if ("String".equals(t) && stringQ != null) {
                String len = childText(stringQ, "length");
                formatted.add(isPositiveInt(len) ? "String(" + len.trim() + ")" : "String");
            } else if ("Number".equals(t) && numberQ != null) {
                String precision = childText(numberQ, "precision");
                String scale = childText(numberQ, "scale");
                if (isPositiveInt(precision)) {
                    formatted.add(isPositiveInt(scale)
                            ? "Number(" + precision.trim() + "," + scale.trim() + ")"
                            : "Number(" + precision.trim() + ")");
                } else {
                    formatted.add("Number");
                }
            } else {
                formatted.add(t);
            }
        }
        return formatted.size() == 1 ? formatted.get(0) : formatted;
    }

    private static boolean isPositiveInt(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            return Integer.parseInt(s.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Reads the {@code ru} value among an element's {@code <synonym>} children, or "". */
    private static String synonymRu(Element parent) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE || !"synonym".equals(k.getNodeName())) {
                continue;
            }
            Element syn = (Element) k;
            if ("ru".equals(childText(syn, "key"))) {
                String v = childText(syn, "value");
                return v != null ? v : "";
            }
        }
        return "";
    }

    // ---

    /**
     * Build {@code <type><types>X</types>...<types>Y</types>[<qualifiers>...</qualifiers>]</type>}
     * для multi-typed Dimension (AccReg). Qualifiers пишутся только для первого типа.
     */
    private static Element buildMultiType(Document doc, List<String> types) throws ToolException {
        if (types == null || types.isEmpty()) {
            throw new ToolException("types is empty");
        }
        Element t = doc.createElement("type");
        // append <types> для каждого; qualifiers — только для первого
        String first = types.get(0);
        appendTypeAndQualifiers(doc, t, first);
        for (int i = 1; i < types.size(); i++) {
            String extra = types.get(i);
            String base = baseOf(extra);
            // вставляем <types>X</types> ПЕРЕД qualifiers — в каноническом .mdo все <types>
            // идут подряд, qualifiers после. Найдём первый non-<types> child и insertBefore.
            Element typesEl = doc.createElement("types");
            typesEl.setTextContent(base);
            Node qualifierAnchor = firstNonTypesChild(t);
            if (qualifierAnchor != null) t.insertBefore(typesEl, qualifierAnchor);
            else                          t.appendChild(typesEl);
        }
        return t;
    }

    private static Node firstNonTypesChild(Element parent) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && !"types".equals(k.getNodeName())) {
                return k;
            }
        }
        return null;
    }

    private static String baseOf(String typeExpr) {
        int paren = typeExpr.indexOf('(');
        return (paren < 0 ? typeExpr : typeExpr.substring(0, paren)).trim();
    }

    /** Append {@code <types>X</types>} + optional qualifiers block to a parent {@code <type>} element. */
    private static void appendTypeAndQualifiers(Document doc, Element typeParent, String type)
            throws ToolException {
        if (type == null || type.isEmpty()) {
            throw new ToolException("type is required");
        }
        int paren = type.indexOf('(');
        String base = (paren < 0 ? type : type.substring(0, paren)).trim();
        String args = paren < 0 ? "" : type.substring(paren + 1, type.lastIndexOf(')')).trim();

        switch (base) {
            case "String": {
                appendText(doc, typeParent, "types", "String");
                Element q = doc.createElement("stringQualifiers");
                if (!args.isEmpty()) appendText(doc, q, "length", args);
                typeParent.appendChild(q);
                break;
            }
            case "Number": {
                appendText(doc, typeParent, "types", "Number");
                Element q = doc.createElement("numberQualifiers");
                if (!args.isEmpty()) {
                    String[] parts = args.split(",");
                    appendText(doc, q, "precision", parts[0].trim());
                    if (parts.length > 1) appendText(doc, q, "scale", parts[1].trim());
                }
                typeParent.appendChild(q);
                break;
            }
            case "Date": {
                appendText(doc, typeParent, "types", "Date");
                Element q = doc.createElement("dateQualifiers");
                if (!args.isEmpty()) appendText(doc, q, "dateFractions", args);
                typeParent.appendChild(q);
                break;
            }
            case "Boolean":
            case "UUID":
            case "ValueStorage":   // «Хранилище значения»
            case "AnyRef":         // «Любая ссылка»
                appendText(doc, typeParent, "types", base);
                break;
            default:
                if (base.contains("Ref.") || base.contains("Object.")) {
                    appendText(doc, typeParent, "types", base);
                } else {
                    throw new ToolException("unsupported type '" + type + "'");
                }
        }
    }

    /** Build {@code <type><types>X</types>[<qualifiers>...</qualifiers>]</type>} for TS attribute. */
    private static Element buildType(Document doc, String type) throws ToolException {
        Element t = doc.createElement("type");
        appendTypeAndQualifiers(doc, t, type);
        return t;
    }

    private static Element findTabularSection(Element root, String name) {
        NodeList list = root.getElementsByTagName("tabularSections");
        for (int i = 0; i < list.getLength(); i++) {
            Element ts = (Element) list.item(i);
            if (ts.getParentNode() != root) continue;
            if (name.equals(childText(ts, "name"))) return ts;
        }
        return null;
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

    private static Node firstChildByName(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) return k;
        }
        return null;
    }

    private static Document load(IFile file) throws ToolException {
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

    private void save(IFile file, Document doc) throws ToolException {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(baos));
            byte[] bytes = baos.toByteArray();
            file.setContents(new ByteArrayInputStream(bytes), true, true, new NullProgressMonitor());
        } catch (TransformerException | CoreException e) {
            throw new ToolException("failed to write " + file.getFullPath() + ": " + e.getMessage());
        }
        if (bmModelManager != null) {
            try {
                bmModelManager.waitModelSynchronization(file.getProject());
            } catch (Throwable ignored) { /* best-effort */ }
        }
    }
}
