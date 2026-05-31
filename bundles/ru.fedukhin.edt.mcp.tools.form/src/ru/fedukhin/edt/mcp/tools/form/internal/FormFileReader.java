package ru.fedukhin.edt.mcp.tools.form.internal;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Disk-read для {@code Form.form} (XML), параллельный {@link FormReader}, который
 * читает то же из BM-модели через рефлексию.
 *
 * <p>Why: после {@code create_form} / {@code add_form_*} BM-модель отстаёт на
 * последнюю mutation (Xtext re-parse не успел), и {@code get_form} возвращает
 * stale данные. Disk-read даёт fresh data всегда. Та же мотивация, что у
 * BUG-16 fix для {@code list_attributes}.
 *
 * <p>{@code Form.form} использует prefix {@code form:} для namespace
 * {@code http://g5.1c.ru/v8/dt/form}. Мы парсим в {@code namespaceAware=false}
 * режиме — XML-структура достаточна для извлечения нужного, NS-handling
 * усложняет код без пользы.
 *
 * <p>Возвращаемая форма совпадает с {@link FormReader}: те же ключи
 * {@code path/name/type/dataPath?} для items, {@code name/type} для attributes,
 * {@code name/title?} для commands.
 */
@Singleton
public class FormFileReader {

    public FormFileReader() { }

    public List<Map<String, Object>> readItemsFlat(IFile formFile) throws ToolException {
        Document doc = load(formFile);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Element item : directChildren(doc.getDocumentElement(), "items")) {
            walkFlat(item, "", out);
        }
        return out;
    }

    public List<Map<String, Object>> readItemsTree(IFile formFile) throws ToolException {
        Document doc = load(formFile);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Element item : directChildren(doc.getDocumentElement(), "items")) {
            out.add(walkTree(item));
        }
        return out;
    }

    public List<Map<String, Object>> readAttributes(IFile formFile) throws ToolException {
        Document doc = load(formFile);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Element attr : directChildren(doc.getDocumentElement(), "attributes")) {
            Map<String, Object> m = new LinkedHashMap<>();
            String name = childText(attr, "name");
            m.put("name", name != null ? name : "");
            Object type = formatValueType(firstChildByTag(attr, "valueType"));
            if (type != null) m.put("type", type);
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> readCommands(IFile formFile) throws ToolException {
        Document doc = load(formFile);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Element cmd : directChildren(doc.getDocumentElement(), "formCommands")) {
            Map<String, Object> m = new LinkedHashMap<>();
            String name = childText(cmd, "name");
            m.put("name", name != null ? name : "");
            String title = extractLocalizedString(firstChildByTag(cmd, "title"));
            if (title != null && !title.isEmpty()) m.put("title", title);
            out.add(m);
        }
        return out;
    }

    public String readTitle(IFile formFile) throws ToolException {
        Document doc = load(formFile);
        return extractLocalizedString(firstChildByTag(doc.getDocumentElement(), "title"));
    }

    // -------------------------------------------------------------------------

    private static void walkFlat(Element item, String parentPath, List<Map<String, Object>> out) {
        String name = childText(item, "name");
        String path = (parentPath == null || parentPath.isEmpty())
                ? (name != null ? name : "")
                : parentPath + "/" + (name != null ? name : "");
        out.add(itemMap(item, path));
        for (Element child : directChildren(item, "items")) {
            walkFlat(child, path, out);
        }
    }

    private static Map<String, Object> walkTree(Element item) {
        Map<String, Object> m = itemMap(item, childText(item, "name"));
        List<Element> children = directChildren(item, "items");
        if (!children.isEmpty()) {
            List<Map<String, Object>> kids = new ArrayList<>();
            for (Element c : children) kids.add(walkTree(c));
            m.put("children", kids);
        }
        return m;
    }

    private static Map<String, Object> itemMap(Element item, String nameOrPath) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", nameOrPath != null ? nameOrPath : "");
        String name = childText(item, "name");
        m.put("name", name != null ? name : "");
        m.put("type", stripFormPrefix(item.getAttribute("xsi:type")));

        Element dp = firstChildByTag(item, "dataPath");
        if (dp != null) {
            String s = joinSegments(dp);
            if (!s.isEmpty()) m.put("dataPath", s);
        }
        return m;
    }

    /** Strip {@code form:} prefix from xsi:type (e.g. "form:FormField" → "FormField"). */
    private static String stripFormPrefix(String xsiType) {
        if (xsiType == null || xsiType.isEmpty()) return "";
        int colon = xsiType.indexOf(':');
        return colon < 0 ? xsiType : xsiType.substring(colon + 1);
    }

    /** Join {@code <segments>X</segments>} children with dots: "Объект.X". */
    private static String joinSegments(Element dp) {
        StringBuilder sb = new StringBuilder();
        for (Element s : directChildren(dp, "segments")) {
            if (sb.length() > 0) sb.append('.');
            sb.append(s.getTextContent());
        }
        return sb.toString();
    }

    /**
     * Извлечь локализованную строку из контейнера типа {@code <title><key>ru</key>
     * <value>X</value>...</title>}. Возвращает RU значение, иначе первое непустое.
     */
    private static String extractLocalizedString(Element loc) {
        if (loc == null) return null;
        // Find the first <key>ru</key> then take its sibling <value>.
        NodeList kids = loc.getChildNodes();
        String firstValue = null;
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE) continue;
            if (!"key".equals(k.getNodeName())) continue;
            if (!"ru".equals(k.getTextContent())) continue;
            // Walk forward to find the next <value>
            for (int j = i + 1; j < kids.getLength(); j++) {
                Node v = kids.item(j);
                if (v.getNodeType() == Node.ELEMENT_NODE && "value".equals(v.getNodeName())) {
                    String s = v.getTextContent();
                    if (s != null && !s.isEmpty()) return s;
                    break;
                }
            }
        }
        // Fallback: first <value>
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "value".equals(n.getNodeName())) {
                String s = n.getTextContent();
                if (s != null && !s.isEmpty()) return s;
            }
        }
        return null;
    }

    /**
     * Форматирует {@code <valueType>} из {@code FormAttribute} в строку или список —
     * для одного типа: {@code "Number(3)"}; для составного: {@code ["String(10)", "Number(P,S)"]}.
     */
    private static Object formatValueType(Element vt) {
        if (vt == null) return null;
        List<String> rawTypes = new ArrayList<>();
        Element stringQ = null;
        Element numberQ = null;
        NodeList kids = vt.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE) continue;
            switch (k.getNodeName()) {
                case "types"            -> rawTypes.add(k.getTextContent().trim());
                case "stringQualifiers" -> stringQ = (Element) k;
                case "numberQualifiers" -> numberQ = (Element) k;
                default                 -> { /* skip dateQualifiers etc. */ }
            }
        }
        if (rawTypes.isEmpty()) return null;
        List<String> formatted = new ArrayList<>(rawTypes.size());
        for (String t : rawTypes) {
            if ("String".equals(t) && stringQ != null) {
                String len = childText(stringQ, "length");
                formatted.add(isPositiveInt(len) ? "String(" + len.trim() + ")" : "String");
            } else if ("Number".equals(t) && numberQ != null) {
                String precision = childText(numberQ, "precision");
                String scale     = childText(numberQ, "scale");
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
        if (s == null || s.isBlank()) return false;
        try { return Integer.parseInt(s.trim()) > 0; } catch (NumberFormatException e) { return false; }
    }

    // --- DOM helpers ---

    private static List<Element> directChildren(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Element firstChildByTag(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String childText(Element parent, String tag) {
        Element e = firstChildByTag(parent, tag);
        return e != null ? e.getTextContent() : null;
    }

    private static Document load(IFile file) throws ToolException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // form:Form имеет prefix form: для http://g5.1c.ru/v8/dt/form.
            // namespaceAware=false — лексический разбор; getNodeName выдаёт "form:Items"-like,
            // но мы используем "items" без prefix'а. Поэтому НУЖЕН namespace-aware режим,
            // чтобы getNodeName выдавало local part.
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(file.getContents());
            return doc;
        } catch (CoreException | ParserConfigurationException | SAXException | IOException e) {
            throw new ToolException("failed to parse " + file.getFullPath() + ": " + e.getMessage());
        }
    }
}
