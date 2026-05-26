package ru.fedukhin.edt.mcp.tools.form.internal;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Утилиты для модификации Form.form (EMF-сериализованного XML формы) через DOM.
 *
 * <p>Подход: загрузить полный XML, манипулировать DOM, сохранить через IFile API
 * (Eclipse заметит изменение + откроет dirty-state в редакторах, если форма
 * уже открыта). Stage 8b ограничивается одиночными insert'ами (attribute,
 * command) — для более сложного редактирования item-tree это будет Stage 8c.
 *
 * <p>ID allocation: каждый item/attribute/command имеет уникальный {@code <id>}
 * в скоупе одной формы. {@link #nextId(Document)} возвращает max+1 текущих ID,
 * включая вложенные contextMenu/extendedTooltip-id.
 */
@Singleton
public class FormFileEditor {

    private static final String FORM_NS = "http://g5.1c.ru/v8/dt/form";
    private static final String XSI_NS  = "http://www.w3.org/2001/XMLSchema-instance";

    private final IBmModelManager bmModelManager;

    @Inject
    public FormFileEditor(IBmModelManager bmModelManager) {
        this.bmModelManager = bmModelManager;
    }

    /** Test seam — позволяет создавать без BM (юнит-тесты DOM-логики). */
    public FormFileEditor() {
        this(null);
    }

    /** Spec для add_form_attribute. */
    public record AttributeSpec(String name, String type, String titleRu, boolean main) { }

    /** Spec для add_form_command. */
    public record CommandSpec(String name, String titleRu, String handlerName) { }

    /** Spec для add_form_field — FormField bound to data path. */
    public record FieldSpec(String name, String dataPath, String parentPath, String titleRu) { }

    /** Spec для add_form_group. groupType ∈ {Pages, Page, UsualGroup}. */
    public record GroupSpec(String name, String groupType, String parentPath, String titleRu) { }

    /** Spec для add_form_button — Button bound to FormCommand by short name. */
    public record ButtonSpec(String name, String commandName, String parentPath, String titleRu) { }

    /** Spec для add_form_table — Table bound to data path (tabular section / dynamic list). */
    public record TableSpec(String name, String dataPath, String parentPath, String titleRu) { }

    /**
     * Spec для set_form_handler.
     * {@code itemPath == null} — form-level handler ({@code <handlers>} на root формы,
     * e.g. ПриСозданииНаСервере, ПриОткрытииНаКлиенте).
     * {@code itemPath != null} — handler на конкретный item (field/group/table/button)
     * по пути через "/" от root.
     */
    public record HandlerSpec(String itemPath, String event, String handlerName) { }

    /** Загрузить XML, добавить &lt;attributes&gt;, сохранить. Бросает если name уже есть. */
    public void addAttribute(IFile formFile, AttributeSpec spec) throws ToolException {
        Document doc = load(formFile);
        Element root = doc.getDocumentElement();

        // Duplicate check
        NodeList existing = root.getElementsByTagName("attributes");
        for (int i = 0; i < existing.getLength(); i++) {
            Element a = (Element) existing.item(i);
            // only direct children of root
            if (a.getParentNode() != root) continue;
            String existingName = childText(a, "name");
            if (spec.name.equals(existingName)) {
                throw new ToolException("attribute '" + spec.name + "' already exists in form '"
                        + formFile.getName() + "'");
            }
        }

        int id = nextId(doc);
        // No-namespace children — match original EDT layout: root has xmlns:form prefix
        // but child elements like <attributes>/<formCommands>/<items> live in empty ns.
        // createElementNS(FORM_NS, ...) would serialize as <attributes xmlns="..."/> — wrong.
        Element attr = doc.createElement("attributes");
        appendText(doc, attr, "name", spec.name);
        if (spec.titleRu != null && !spec.titleRu.isEmpty()) {
            Element title = doc.createElement("title");
            appendText(doc, title, "key", "ru");
            appendText(doc, title, "value", spec.titleRu);
            attr.appendChild(title);
        }
        appendText(doc, attr, "id", Integer.toString(id));
        attr.appendChild(buildValueType(doc, spec.type));
        Element view = doc.createElement("view");
        appendText(doc, view, "common", "true");
        attr.appendChild(view);
        Element edit = doc.createElement("edit");
        appendText(doc, edit, "common", "true");
        attr.appendChild(edit);
        if (spec.main) {
            appendText(doc, attr, "main", "true");
            appendText(doc, attr, "savedData", "true");
        }

        // Insert before <commandInterface> if it exists; else before <extInfo>; else at end.
        Node anchor = firstChildByName(root, "commandInterface");
        if (anchor == null) anchor = firstChildByName(root, "extInfo");
        if (anchor != null) {
            root.insertBefore(attr, anchor);
        } else {
            root.appendChild(attr);
        }

        save(formFile, doc);
    }

    /** Загрузить XML, добавить &lt;formCommands&gt;, сохранить. */
    public void addCommand(IFile formFile, CommandSpec spec) throws ToolException {
        Document doc = load(formFile);
        Element root = doc.getDocumentElement();

        // Duplicate check
        NodeList existing = root.getElementsByTagName("formCommands");
        for (int i = 0; i < existing.getLength(); i++) {
            Element c = (Element) existing.item(i);
            if (c.getParentNode() != root) continue;
            String existingName = childText(c, "name");
            if (spec.name.equals(existingName)) {
                throw new ToolException("command '" + spec.name + "' already exists in form '"
                        + formFile.getName() + "'");
            }
        }

        int id = nextId(doc);
        Element cmd = doc.createElement("formCommands");
        appendText(doc, cmd, "name", spec.name);
        if (spec.titleRu != null && !spec.titleRu.isEmpty()) {
            Element title = doc.createElement("title");
            appendText(doc, title, "key", "ru");
            appendText(doc, title, "value", spec.titleRu);
            cmd.appendChild(title);
        }
        appendText(doc, cmd, "id", Integer.toString(id));
        Element use = doc.createElement("use");
        appendText(doc, use, "common", "true");
        cmd.appendChild(use);
        // action with handler
        Element action = doc.createElement("action");
        action.setAttributeNS(XSI_NS, "xsi:type", "form:FormCommandHandlerContainer");
        Element handler = doc.createElement("handler");
        appendText(doc, handler, "name",
                spec.handlerName != null && !spec.handlerName.isEmpty()
                        ? spec.handlerName : spec.name);
        action.appendChild(handler);
        cmd.appendChild(action);
        appendText(doc, cmd, "currentRowUse", "Auto");

        Node anchor = firstChildByName(root, "commandInterface");
        if (anchor == null) anchor = firstChildByName(root, "extInfo");
        if (anchor != null) {
            root.insertBefore(cmd, anchor);
        } else {
            root.appendChild(cmd);
        }

        save(formFile, doc);
    }

    // ----------------------------------------------------------------
    // Stage 8c: UI items — fields, groups, buttons, tables, handlers
    // ----------------------------------------------------------------

    /** Добавить FormField (InputField, биндинг к dataPath). */
    public void addField(IFile formFile, FieldSpec spec) throws ToolException {
        Document doc = load(formFile);
        Element root = doc.getDocumentElement();
        Element parentItem = resolveParent(root, spec.parentPath);

        // Duplicate check в parent scope (по name среди прямых <items>)
        if (findItemChildByName(parentItem, spec.name) != null) {
            throw new ToolException("item '" + spec.name + "' already exists in "
                    + (spec.parentPath == null ? "form root" : "group '" + spec.parentPath + "'"));
        }

        int id    = nextId(doc);
        int tipId = nextIdAfter(doc, id);
        int menId = nextIdAfter(doc, tipId);

        Element item = doc.createElement("items");
        item.setAttributeNS(XSI_NS, "xsi:type", "form:FormField");
        appendText(doc, item, "name", spec.name);
        appendText(doc, item, "id",   Integer.toString(id));
        if (spec.titleRu != null && !spec.titleRu.isEmpty()) {
            Element title = doc.createElement("title");
            appendText(doc, title, "key", "ru");
            appendText(doc, title, "value", spec.titleRu);
            item.appendChild(title);
        }
        appendText(doc, item, "visible", "true");
        appendText(doc, item, "enabled", "true");
        Element uv = doc.createElement("userVisible");
        appendText(doc, uv, "common", "true");
        item.appendChild(uv);
        Element dp = doc.createElement("dataPath");
        dp.setAttributeNS(XSI_NS, "xsi:type", "form:DataPath");
        appendText(doc, dp, "segments", spec.dataPath);
        item.appendChild(dp);
        item.appendChild(buildExtendedTooltip(doc, spec.name, tipId));
        item.appendChild(buildContextMenu(doc, spec.name, menId));
        appendText(doc, item, "type", "InputField");
        appendText(doc, item, "editMode", "Enter");
        Element extInfo = doc.createElement("extInfo");
        extInfo.setAttributeNS(XSI_NS, "xsi:type", "form:InputFieldExtInfo");
        appendText(doc, extInfo, "autoMaxWidth", "true");
        appendText(doc, extInfo, "autoMaxHeight", "true");
        appendText(doc, extInfo, "chooseType", "true");
        appendText(doc, extInfo, "typeDomainEnabled", "true");
        appendText(doc, extInfo, "textEdit", "true");
        item.appendChild(extInfo);

        insertItem(root, parentItem, item);
        save(formFile, doc);
    }

    /** Добавить FormGroup типа Pages/Page/UsualGroup. */
    public void addGroup(IFile formFile, GroupSpec spec) throws ToolException {
        Document doc = load(formFile);
        Element root = doc.getDocumentElement();
        Element parentItem = resolveParent(root, spec.parentPath);

        if (findItemChildByName(parentItem, spec.name) != null) {
            throw new ToolException("item '" + spec.name + "' already exists in "
                    + (spec.parentPath == null ? "form root" : "group '" + spec.parentPath + "'"));
        }

        int id    = nextId(doc);
        int tipId = nextIdAfter(doc, id);

        Element item = doc.createElement("items");
        item.setAttributeNS(XSI_NS, "xsi:type", "form:FormGroup");
        appendText(doc, item, "name", spec.name);
        appendText(doc, item, "id",   Integer.toString(id));
        appendText(doc, item, "visible", "true");
        appendText(doc, item, "enabled", "true");
        Element uv = doc.createElement("userVisible");
        appendText(doc, uv, "common", "true");
        item.appendChild(uv);
        if (spec.titleRu != null && !spec.titleRu.isEmpty()) {
            Element title = doc.createElement("title");
            appendText(doc, title, "key", "ru");
            appendText(doc, title, "value", spec.titleRu);
            item.appendChild(title);
        }
        item.appendChild(buildExtendedTooltip(doc, spec.name, tipId));

        String typeTag;
        String extInfoType;
        Element extInfo = doc.createElement("extInfo");
        switch (spec.groupType) {
            case "Pages":
                typeTag = "Pages";
                extInfoType = "form:PagesGroupExtInfo";
                extInfo.setAttributeNS(XSI_NS, "xsi:type", extInfoType);
                appendText(doc, extInfo, "currentRowUse", "Auto");
                break;
            case "Page":
                typeTag = "Page";
                extInfoType = "form:PageGroupExtInfo";
                extInfo.setAttributeNS(XSI_NS, "xsi:type", extInfoType);
                appendText(doc, extInfo, "group", "Vertical");
                appendText(doc, extInfo, "showTitle", "true");
                break;
            case "UsualGroup":
                typeTag = "UsualGroup";
                extInfoType = "form:UsualGroupExtInfo";
                extInfo.setAttributeNS(XSI_NS, "xsi:type", extInfoType);
                appendText(doc, extInfo, "group", "Vertical");
                appendText(doc, extInfo, "showLeftMargin", "true");
                appendText(doc, extInfo, "united", "true");
                appendText(doc, extInfo, "throughAlign", "Auto");
                appendText(doc, extInfo, "currentRowUse", "Auto");
                break;
            default:
                throw new ToolException("groupType must be one of {Pages, Page, UsualGroup}, got: '"
                        + spec.groupType + "'");
        }
        appendText(doc, item, "type", typeTag);
        item.appendChild(extInfo);

        insertItem(root, parentItem, item);
        save(formFile, doc);
    }

    /**
     * Добавить Button типа UsualButton, биндинг к Form.Command.{commandName}.
     * Команда с этим именем должна уже существовать (через add_form_command),
     * иначе кнопка будет ссылаться на несуществующее — EDT покажет ошибку
     * при открытии формы.
     */
    public void addButton(IFile formFile, ButtonSpec spec) throws ToolException {
        Document doc = load(formFile);
        Element root = doc.getDocumentElement();
        Element parentItem = resolveParent(root, spec.parentPath);

        if (findItemChildByName(parentItem, spec.name) != null) {
            throw new ToolException("item '" + spec.name + "' already exists in "
                    + (spec.parentPath == null ? "form root" : "group '" + spec.parentPath + "'"));
        }

        int id    = nextId(doc);
        int tipId = nextIdAfter(doc, id);

        Element item = doc.createElement("items");
        item.setAttributeNS(XSI_NS, "xsi:type", "form:Button");
        appendText(doc, item, "name", spec.name);
        appendText(doc, item, "id",   Integer.toString(id));
        if (spec.titleRu != null && !spec.titleRu.isEmpty()) {
            Element title = doc.createElement("title");
            appendText(doc, title, "key", "ru");
            appendText(doc, title, "value", spec.titleRu);
            item.appendChild(title);
        }
        appendText(doc, item, "visible", "true");
        appendText(doc, item, "enabled", "true");
        Element uv = doc.createElement("userVisible");
        appendText(doc, uv, "common", "true");
        item.appendChild(uv);
        item.appendChild(buildExtendedTooltip(doc, spec.name, tipId));
        appendText(doc, item, "type", "UsualButton");
        appendText(doc, item, "commandName", "Form.Command." + spec.commandName);
        appendText(doc, item, "representation", "Auto");

        insertItem(root, parentItem, item);
        save(formFile, doc);
    }

    /**
     * Добавить Table (для табличной части или динамического списка).
     * dataPath — обычно имя реквизита формы типа DynamicList или TabularSection,
     * e.g. "Список" или "Товары".
     */
    public void addTable(IFile formFile, TableSpec spec) throws ToolException {
        Document doc = load(formFile);
        Element root = doc.getDocumentElement();
        Element parentItem = resolveParent(root, spec.parentPath);

        if (findItemChildByName(parentItem, spec.name) != null) {
            throw new ToolException("item '" + spec.name + "' already exists in "
                    + (spec.parentPath == null ? "form root" : "group '" + spec.parentPath + "'"));
        }

        int id = nextId(doc);

        Element item = doc.createElement("items");
        item.setAttributeNS(XSI_NS, "xsi:type", "form:Table");
        appendText(doc, item, "name", spec.name);
        appendText(doc, item, "id",   Integer.toString(id));
        if (spec.titleRu != null && !spec.titleRu.isEmpty()) {
            Element title = doc.createElement("title");
            appendText(doc, title, "key", "ru");
            appendText(doc, title, "value", spec.titleRu);
            item.appendChild(title);
        }
        appendText(doc, item, "visible", "true");
        appendText(doc, item, "enabled", "true");
        Element uv = doc.createElement("userVisible");
        appendText(doc, uv, "common", "true");
        item.appendChild(uv);
        Element dp = doc.createElement("dataPath");
        dp.setAttributeNS(XSI_NS, "xsi:type", "form:DataPath");
        appendText(doc, dp, "segments", spec.dataPath);
        item.appendChild(dp);
        appendText(doc, item, "titleLocation", "None");

        insertItem(root, parentItem, item);
        save(formFile, doc);
    }

    /**
     * Добавить {@code <handlers>} на root формы или на конкретный item.
     * itemPath = null → root form handler. Event ∈ {OnCreateAtServer, OnOpen,
     * OnChange, Click, BeforeWrite, ...} — список конкретных event'ов зависит
     * от kind хоста.
     */
    public void setHandler(IFile formFile, HandlerSpec spec) throws ToolException {
        Document doc = load(formFile);
        Element root = doc.getDocumentElement();
        Element host = (spec.itemPath == null || spec.itemPath.isEmpty())
                ? root
                : resolveParent(root, spec.itemPath);

        // Duplicate check
        NodeList handlers = host.getChildNodes();
        for (int i = 0; i < handlers.getLength(); i++) {
            Node n = handlers.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "handlers".equals(n.getNodeName())) {
                String existingEvent = childText((Element) n, "event");
                if (spec.event.equals(existingEvent)) {
                    throw new ToolException("handler for event '" + spec.event + "' already exists on "
                            + (spec.itemPath == null ? "form root" : "item '" + spec.itemPath + "'"));
                }
            }
        }

        Element handler = doc.createElement("handlers");
        appendText(doc, handler, "event", spec.event);
        appendText(doc, handler, "name",  spec.handlerName);

        // Insert near top — стандартный layout EDT держит <handlers> рядом с заголовком блока:
        // для root формы — после <autoCommandBar>/<windowOpeningMode>/...; для item — после
        // <userVisible>/<dataPath>/<title>. Чтобы не возиться с anchor — append; EDT при
        // переоткрытии reorder'нет в свой канон.
        host.appendChild(handler);
        save(formFile, doc);
    }

    // --- shared internals для 8c ---

    /** Build standard extendedTooltip block. */
    private static Element buildExtendedTooltip(Document doc, String parentName, int id) {
        Element tip = doc.createElement("extendedTooltip");
        appendText(doc, tip, "name", parentName + "РасширеннаяПодсказка");
        appendText(doc, tip, "id", Integer.toString(id));
        appendText(doc, tip, "type", "Label");
        appendText(doc, tip, "autoMaxWidth", "true");
        appendText(doc, tip, "autoMaxHeight", "true");
        Element ei = doc.createElement("extInfo");
        ei.setAttributeNS(XSI_NS, "xsi:type", "form:LabelDecorationExtInfo");
        appendText(doc, ei, "horizontalAlign", "Left");
        tip.appendChild(ei);
        return tip;
    }

    /** Build standard contextMenu block. */
    private static Element buildContextMenu(Document doc, String parentName, int id) {
        Element ctx = doc.createElement("contextMenu");
        appendText(doc, ctx, "name", parentName + "КонтекстноеМеню");
        appendText(doc, ctx, "id", Integer.toString(id));
        appendText(doc, ctx, "autoFill", "true");
        return ctx;
    }

    /**
     * Найти parent EObject для inserting item. {@code path == null} → root;
     * иначе walk "/" segments через дочерние {@code <items>} по {@code <name>}.
     */
    private static Element resolveParent(Element root, String path) throws ToolException {
        if (path == null || path.isEmpty()) return root;
        Element current = root;
        for (String segment : path.split("/")) {
            Element next = findItemChildByName(current, segment);
            if (next == null) {
                throw new ToolException("group path segment '" + segment + "' not found "
                        + "(walking path '" + path + "')");
            }
            current = next;
        }
        return current;
    }

    /** Найти прямой {@code <items>}-child по {@code <name>NAME</name>}. */
    private static Element findItemChildByName(Element parent, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && "items".equals(k.getNodeName())) {
                String n = childText((Element) k, "name");
                if (name.equals(n)) return (Element) k;
            }
        }
        return null;
    }

    /**
     * Вставить item в parent. Для root — same anchor logic как addAttribute
     * (before commandInterface/extInfo). Для nested parent — append.
     */
    private static void insertItem(Element root, Element parent, Element newItem) {
        if (parent == root) {
            Node anchor = firstChildByName(root, "commandBarLocation");
            if (anchor == null) anchor = firstChildByName(root, "autoCommandBar");
            if (anchor == null) anchor = firstChildByName(root, "windowOpeningMode");
            if (anchor == null) anchor = firstChildByName(root, "attributes");
            if (anchor == null) anchor = firstChildByName(root, "commandInterface");
            if (anchor == null) anchor = firstChildByName(root, "extInfo");
            if (anchor != null) root.insertBefore(newItem, anchor);
            else                root.appendChild(newItem);
        } else {
            parent.appendChild(newItem);
        }
    }

    /** Следующий ID > {@code prev} среди существующих + ещё неиспользованных. */
    private static int nextIdAfter(Document doc, int prev) {
        return prev + 1;
    }

    /** Найти max существующий {@code <id>} в скоупе документа + 1. */
    static int nextId(Document doc) {
        NodeList all = doc.getElementsByTagName("id");
        int max = 0;
        for (int i = 0; i < all.getLength(); i++) {
            try {
                int v = Integer.parseInt(all.item(i).getTextContent().trim());
                if (v > max) max = v;
            } catch (NumberFormatException ignore) { /* skip */ }
        }
        return max + 1;
    }

    /** Построить {@code <valueType>} по type-выражению ("String(50)", "Number(10,2)", ...). */
    private static Element buildValueType(Document doc, String type) throws ToolException {
        Element vt = doc.createElement("valueType");
        if (type == null || type.isEmpty()) {
            throw new ToolException("attribute type is required");
        }
        // Parse: BaseType[(args)] — args зависят от base
        int paren = type.indexOf('(');
        String base = (paren < 0 ? type : type.substring(0, paren)).trim();
        String args = paren < 0 ? "" : type.substring(paren + 1, type.lastIndexOf(')')).trim();

        switch (base) {
            case "String": {
                appendText(doc, vt, "types", "String");
                Element q = doc.createElement("stringQualifiers");
                if (!args.isEmpty()) {
                    appendText(doc, q, "length", args);
                }
                vt.appendChild(q);
                break;
            }
            case "Number": {
                appendText(doc, vt, "types", "Number");
                Element q = doc.createElement("numberQualifiers");
                if (!args.isEmpty()) {
                    String[] parts = args.split(",");
                    appendText(doc, q, "precision", parts[0].trim());
                    if (parts.length > 1) {
                        appendText(doc, q, "scale", parts[1].trim());
                    }
                }
                vt.appendChild(q);
                break;
            }
            case "Date": {
                appendText(doc, vt, "types", "Date");
                Element q = doc.createElement("dateQualifiers");
                if (!args.isEmpty()) {
                    appendText(doc, q, "dateFractions", args);
                }
                vt.appendChild(q);
                break;
            }
            case "Boolean":
            case "UUID":
                appendText(doc, vt, "types", base);
                break;
            default:
                // Reference type: CatalogRef.X, DocumentRef.X, EnumRef.X, etc.
                if (base.contains("Ref.") || base.contains("Object.")) {
                    appendText(doc, vt, "types", base);
                } else {
                    throw new ToolException("unsupported attribute type '" + type + "'");
                }
        }
        return vt;
    }

    // --- DOM helpers ---

    private static void appendText(Document doc, Element parent, String tag, String text) {
        Element e = doc.createElement(tag);
        e.setTextContent(text);
        parent.appendChild(e);
    }

    private static String childText(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        for (int i = 0; i < n.getLength(); i++) {
            Node node = n.item(i);
            if (node.getParentNode() == parent) {
                return node.getTextContent();
            }
        }
        return null;
    }

    private static Node firstChildByName(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) {
                return k;
            }
        }
        return null;
    }

    private static Document load(IFile file) throws ToolException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(file.getContents());
            // Strip whitespace-only text nodes — иначе indent=yes у Transformer'а
            // удваивает/утроивает существующие newlines+spaces.
            stripWhitespaceNodes(doc.getDocumentElement());
            return doc;
        } catch (CoreException | ParserConfigurationException | SAXException | IOException e) {
            throw new ToolException("failed to parse " + file.getFullPath() + ": " + e.getMessage());
        }
    }

    private static void stripWhitespaceNodes(Node node) {
        NodeList kids = node.getChildNodes();
        // Reverse-iterate so removal doesn't shift indices.
        for (int i = kids.getLength() - 1; i >= 0; i--) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.TEXT_NODE && k.getTextContent().isBlank()) {
                node.removeChild(k);
            } else if (k.getNodeType() == Node.ELEMENT_NODE) {
                stripWhitespaceNodes(k);
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
        // Task #11: BM lazy-loads .form Resource. После write через IFile API
        // Eclipse fires resource-change, но BM-sync асинхронный. Без блокирующего
        // wait следующий get_form / get_form_item читает стейл item-tree
        // (Stage 8c smoke step 10 failure 2026-05-18 показал). Дешёвый sync
        // через waitModelSynchronization.
        if (bmModelManager != null) {
            try {
                bmModelManager.waitModelSynchronization(file.getProject());
            } catch (Throwable t) {
                // Best-effort; sync-failure не должен сворачивать успешную DOM-операцию.
                // .form уже на диске, EDT-editor подцепит при reopen.
            }
        }
    }
}
