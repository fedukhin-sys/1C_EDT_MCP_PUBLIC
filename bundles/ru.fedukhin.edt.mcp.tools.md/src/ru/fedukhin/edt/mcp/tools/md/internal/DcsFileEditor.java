package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
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
 * Stage 8f extension — DOM-based editor для {@code Template.dcs} файлов DataCompositionSchema.
 *
 * <p>Параллель {@link MdoFileEditor} для .mdo. В отличие от .mdo, .dcs имеет
 * <i>default xmlns</i> (schema-namespace) — поэтому работаем в
 * {@code namespaceAware=false} режиме (лексический разбор). Иначе createElement
 * без NS приведёт к {@code xmlns=""} override на дочерних элементах.
 *
 * <p>Поддерживаемые операции:
 * <ul>
 *   <li>{@link #addDataSetQuery} — добавить новый {@code <dataSet xsi:type="DataSetQuery">};</li>
 *   <li>{@link #addDataSetField} — добавить {@code <field xsi:type="DataSetFieldField">}
 *       внутрь существующего dataSet'а;</li>
 *   <li>{@link #addParameter} — добавить {@code <parameter>} в root.</li>
 * </ul>
 *
 * <p>Все операции идемпотентны: повторное добавление с тем же именем — no-op (return false).
 */
@Singleton
public class DcsFileEditor {

    private final IBmModelManager bmModelManager;

    @Inject
    public DcsFileEditor(IBmModelManager bmModelManager) {
        this.bmModelManager = bmModelManager;
    }

    /** Test seam. */
    public DcsFileEditor() { this(null); }

    /**
     * Добавить {@code <dataSet xsi:type="DataSetQuery">} в .dcs.
     *
     * <p>Структура: {@code <name>X</name><dataSource>Y</dataSource><query>...</query>}.
     * dataSource — имя существующего {@code <dataSource>} в .dcs (root-level).
     * Если такого нет — добавляет первый встретившийся; если их 0 — error.
     *
     * <p>Идемпотентно: если dataSet с таким именем уже есть — no-op.
     *
     * @param dataSetName имя нового DataSet'а (нескалярный — root child)
     * @param dataSource  имя dataSource'а внутри .dcs (по умолчанию первый)
     * @param query       текст запроса (на встроенном языке 1С); может быть пустым
     * @return {@code true} если был добавлен, {@code false} если идемпотентно пропущен
     */
    public boolean addDataSetQuery(IFile dcsFile, String dataSetName, String dataSource, String query)
            throws ToolException {
        if (dataSetName == null || dataSetName.isEmpty()) {
            throw new ToolException("dataSetName required");
        }
        Document doc = load(dcsFile);
        boolean changed = applyAddDataSetQuery(doc, dataSetName, dataSource, query);
        if (changed) save(dcsFile, doc);
        return changed;
    }

    /** Pure-DOM helper для {@link #addDataSetQuery}; тестируется без живого IFile. */
    public static boolean applyAddDataSetQuery(Document doc, String dataSetName,
                                               String dataSource, String query) throws ToolException {
        Element root = doc.getDocumentElement();
        if (root == null) throw new ToolException("empty .dcs document");

        // Dup check
        if (findChildByNameTag(root, "dataSet", dataSetName) != null) {
            return false;
        }

        // Resolve dataSource
        String dsName = dataSource;
        if (dsName == null || dsName.isEmpty()) {
            Element firstDs = firstChildByTag(root, "dataSource");
            if (firstDs == null) {
                throw new ToolException(".dcs has no <dataSource>; specify dataSource arg");
            }
            dsName = childText(firstDs, "name");
            if (dsName == null || dsName.isEmpty()) {
                throw new ToolException(".dcs <dataSource> has no <name>");
            }
        }

        Element dataSet = doc.createElement("dataSet");
        dataSet.setAttribute("xsi:type", "DataSetQuery");
        appendText(doc, dataSet, "name", dataSetName);
        appendText(doc, dataSet, "dataSource", dsName);
        appendText(doc, dataSet, "query", query != null ? query : "");

        // Insert before first <settingsVariant>/<parameter>/<calculatedField>/<totalField>,
        // иначе после последнего <dataSet>, иначе после <dataSource>, иначе append.
        Node anchor = firstChildByTag(root, "settingsVariant");
        if (anchor == null) anchor = firstChildByTag(root, "parameter");
        if (anchor == null) anchor = firstChildByTag(root, "calculatedField");
        if (anchor == null) anchor = firstChildByTag(root, "totalField");
        if (anchor != null) root.insertBefore(dataSet, anchor);
        else                root.appendChild(dataSet);
        return true;
    }

    /**
     * Добавить {@code <field xsi:type="DataSetFieldField">} внутрь dataSet'а с указанным именем.
     *
     * <p>Структура: {@code <dataPath>X</dataPath><field>X</field>[<title>...</title>]}.
     * title (если non-null) пишется как {@code <title xsi:type="v8:LocalStringType">
     * <v8:item><v8:lang>ru</v8:lang><v8:content>title</v8:content></v8:item></title>}.
     *
     * <p>Идемпотентно: если field с таким fieldName/dataPath уже есть — no-op.
     *
     * @param dataSetName имя dataSet'а (must exist)
     * @param fieldName   имя поля (= dataPath = field)
     * @param title       русский заголовок (null/empty — без title block)
     * @return {@code true} если добавлен, {@code false} если идемпотентно пропущен
     */
    public boolean addDataSetField(IFile dcsFile, String dataSetName,
                                   String fieldName, String title) throws ToolException {
        if (dataSetName == null || dataSetName.isEmpty()) {
            throw new ToolException("dataSetName required");
        }
        if (fieldName == null || fieldName.isEmpty()) {
            throw new ToolException("fieldName required");
        }
        Document doc = load(dcsFile);
        boolean changed = applyAddDataSetField(doc, dataSetName, fieldName, title);
        if (changed) save(dcsFile, doc);
        return changed;
    }

    /** Pure-DOM helper для {@link #addDataSetField}. */
    public static boolean applyAddDataSetField(Document doc, String dataSetName,
                                               String fieldName, String title) throws ToolException {
        Element root = doc.getDocumentElement();
        if (root == null) throw new ToolException("empty .dcs document");

        Element dataSet = findChildByNameTag(root, "dataSet", dataSetName);
        if (dataSet == null) {
            throw new ToolException("dataSet '" + dataSetName + "' not found in .dcs");
        }

        // Dup check inside dataSet
        NodeList fields = dataSet.getElementsByTagName("field");
        for (int i = 0; i < fields.getLength(); i++) {
            Element f = (Element) fields.item(i);
            if (f.getParentNode() != dataSet) continue;
            // direct <field> child of dataSet — а не вложенный <field> внутри другого <field>
            if (fieldName.equals(f.getTextContent())) return false;
            // также проверим dataPath child (для xsi:type=DataSetFieldField)
            Element dp = firstChildByTag(f, "dataPath");
            if (dp != null && fieldName.equals(dp.getTextContent())) return false;
        }

        Element field = doc.createElement("field");
        field.setAttribute("xsi:type", "DataSetFieldField");
        appendText(doc, field, "dataPath", fieldName);
        appendText(doc, field, "field",    fieldName);
        if (title != null && !title.isEmpty()) {
            field.appendChild(buildTitle(doc, title));
        }

        // Insert before <dataSource>/<query> внутри dataSet, иначе append.
        Node anchor = firstChildByTag(dataSet, "dataSource");
        if (anchor == null) anchor = firstChildByTag(dataSet, "query");
        if (anchor != null) dataSet.insertBefore(field, anchor);
        else                dataSet.appendChild(field);
        return true;
    }

    /**
     * Добавить {@code <parameter>} в root .dcs.
     *
     * <p>Структура: {@code <name>X</name>[<title>...</title>]<valueType><v8:Type>T</v8:Type></valueType>
     * <value xsi:nil="true"/><useRestriction>false</useRestriction>}.
     *
     * <p>valueType — XSD-имя ({@code xs:boolean}, {@code xs:string}, {@code xs:dateTime},
     * {@code xs:decimal}) или 1С-тип в виде {@code v8:CatalogRef.X}. Если null — пропускается
     * (untyped параметр, как в каноне для composite).
     *
     * <p>Идемпотентно: если параметр с таким именем уже есть — no-op.
     */
    public boolean addParameter(IFile dcsFile, String parameterName, String valueType, String title)
            throws ToolException {
        if (parameterName == null || parameterName.isEmpty()) {
            throw new ToolException("parameterName required");
        }
        Document doc = load(dcsFile);
        boolean changed = applyAddParameter(doc, parameterName, valueType, title);
        if (changed) save(dcsFile, doc);
        return changed;
    }

    /** Pure-DOM helper для {@link #addParameter}. */
    public static boolean applyAddParameter(Document doc, String parameterName,
                                            String valueType, String title) throws ToolException {
        Element root = doc.getDocumentElement();
        if (root == null) throw new ToolException("empty .dcs document");

        // Dup check
        if (findChildByNameTag(root, "parameter", parameterName) != null) {
            return false;
        }

        Element param = doc.createElement("parameter");
        appendText(doc, param, "name", parameterName);
        if (title != null && !title.isEmpty()) {
            param.appendChild(buildTitle(doc, title));
        }
        if (valueType != null && !valueType.isEmpty()) {
            Element vt = doc.createElement("valueType");
            Element t  = doc.createElement("v8:Type");
            t.setTextContent(valueType);
            vt.appendChild(t);
            param.appendChild(vt);
        }
        Element nil = doc.createElement("value");
        nil.setAttribute("xsi:nil", "true");
        param.appendChild(nil);
        appendText(doc, param, "useRestriction", "false");

        // Insert before <settingsVariant>; иначе после последнего <parameter>;
        // иначе после <totalField>/<calculatedField>; иначе append.
        Node anchor = firstChildByTag(root, "settingsVariant");
        if (anchor != null) root.insertBefore(param, anchor);
        else                root.appendChild(param);
        return true;
    }

    /**
     * Заменить текст {@code <query>} в существующем {@code <dataSet xsi:type="DataSetQuery">}.
     *
     * <p>Use case: skeleton .dcs создаёт DataSetObject; пользователь добавляет DataSetQuery
     * через {@code add_dcs_data_set_query} (с начальным query). Позже хочется заменить query
     * — этот метод делает replace-or-throw.
     *
     * @throws ToolException если dataSet не найден ИЛИ это не DataSetQuery (xsi:type)
     */
    public boolean setDataSetQuery(IFile dcsFile, String dataSetName, String newQuery)
            throws ToolException {
        if (dataSetName == null || dataSetName.isEmpty()) {
            throw new ToolException("dataSetName required");
        }
        if (newQuery == null) {
            throw new ToolException("query required (use empty string for empty query)");
        }
        Document doc = load(dcsFile);
        boolean changed = applySetDataSetQuery(doc, dataSetName, newQuery);
        if (changed) save(dcsFile, doc);
        return changed;
    }

    /** Pure-DOM helper для {@link #setDataSetQuery}. */
    public static boolean applySetDataSetQuery(Document doc, String dataSetName, String newQuery)
            throws ToolException {
        Element root = doc.getDocumentElement();
        if (root == null) throw new ToolException("empty .dcs document");
        Element dataSet = findChildByNameTag(root, "dataSet", dataSetName);
        if (dataSet == null) {
            throw new ToolException("dataSet '" + dataSetName + "' not found in .dcs");
        }
        String xsiType = dataSet.getAttribute("xsi:type");
        if (!"DataSetQuery".equals(xsiType)) {
            throw new ToolException("dataSet '" + dataSetName + "' is not DataSetQuery (xsi:type='"
                    + xsiType + "'); set_dcs_query_text only works on DataSetQuery");
        }

        Element existing = firstChildByTag(dataSet, "query");
        if (existing != null && newQuery.equals(existing.getTextContent())) {
            return false; // idempotent — same text
        }
        if (existing != null) {
            existing.setTextContent(newQuery);
        } else {
            appendText(doc, dataSet, "query", newQuery);
        }
        return true;
    }

    /**
     * Добавить {@code <calculatedField>} в root .dcs.
     *
     * <p>Структура: {@code <dataPath>X</dataPath><expression>...</expression>
     * [<title>...</title>]}.
     *
     * <p>Идемпотентно по {@code dataPath}.
     */
    public boolean addCalculatedField(IFile dcsFile, String dataPath, String expression, String title)
            throws ToolException {
        if (dataPath == null || dataPath.isEmpty()) {
            throw new ToolException("dataPath required");
        }
        if (expression == null) {
            throw new ToolException("expression required");
        }
        Document doc = load(dcsFile);
        boolean changed = applyAddCalculatedField(doc, dataPath, expression, title);
        if (changed) save(dcsFile, doc);
        return changed;
    }

    /** Pure-DOM helper для {@link #addCalculatedField}. */
    public static boolean applyAddCalculatedField(Document doc, String dataPath,
                                                  String expression, String title)
            throws ToolException {
        Element root = doc.getDocumentElement();
        if (root == null) throw new ToolException("empty .dcs document");

        // Dup check по dataPath
        if (findChildByDataPathTag(root, "calculatedField", dataPath) != null) {
            return false;
        }

        Element cf = doc.createElement("calculatedField");
        appendText(doc, cf, "dataPath",   dataPath);
        appendText(doc, cf, "expression", expression);
        if (title != null && !title.isEmpty()) {
            cf.appendChild(buildTitle(doc, title));
        }

        // Канон: calculatedField идёт после dataSet'ов, перед totalField/parameter/settingsVariant.
        Node anchor = firstChildByTag(root, "totalField");
        if (anchor == null) anchor = firstChildByTag(root, "parameter");
        if (anchor == null) anchor = firstChildByTag(root, "settingsVariant");
        if (anchor != null) root.insertBefore(cf, anchor);
        else                root.appendChild(cf);
        return true;
    }

    /**
     * Добавить {@code <totalField>} в root .dcs.
     *
     * <p>Структура: {@code <dataPath>X</dataPath><expression>...</expression>
     * <group>K1</group>[<group>K2</group>...]}.
     *
     * <p>{@code groupKeys} — список ключей-группировок, для которых работает агрегат.
     * Пустой список допустим (общий total без привязки к группе).
     *
     * <p>Идемпотентно по {@code dataPath}.
     */
    public boolean addTotalField(IFile dcsFile, String dataPath, String expression,
                                  List<String> groupKeys) throws ToolException {
        if (dataPath == null || dataPath.isEmpty()) {
            throw new ToolException("dataPath required");
        }
        if (expression == null || expression.isEmpty()) {
            throw new ToolException("expression required");
        }
        Document doc = load(dcsFile);
        boolean changed = applyAddTotalField(doc, dataPath, expression, groupKeys);
        if (changed) save(dcsFile, doc);
        return changed;
    }

    /** Pure-DOM helper для {@link #addTotalField}. */
    public static boolean applyAddTotalField(Document doc, String dataPath, String expression,
                                              List<String> groupKeys) throws ToolException {
        Element root = doc.getDocumentElement();
        if (root == null) throw new ToolException("empty .dcs document");

        if (findChildByDataPathTag(root, "totalField", dataPath) != null) {
            return false;
        }

        Element tf = doc.createElement("totalField");
        appendText(doc, tf, "dataPath",   dataPath);
        appendText(doc, tf, "expression", expression);
        if (groupKeys != null) {
            for (String g : groupKeys) {
                if (g != null && !g.isEmpty()) appendText(doc, tf, "group", g);
            }
        }

        Node anchor = firstChildByTag(root, "parameter");
        if (anchor == null) anchor = firstChildByTag(root, "settingsVariant");
        if (anchor != null) root.insertBefore(tf, anchor);
        else                root.appendChild(tf);
        return true;
    }

    /**
     * Добавить {@code <dataSetLink>} в root .dcs — master-detail связь между двумя DataSet'ами.
     *
     * <p>Структура: {@code <sourceDataSet>X</sourceDataSet><destinationDataSet>Y</destinationDataSet>
     * <sourceExpression>...</sourceExpression><destinationExpression>...</destinationExpression>
     * [<parameter>...</parameter>][<parameterListAllowed>false</parameterListAllowed>]}.
     *
     * <p>Оба DataSet'а должны существовать в .dcs. {@code parameter} (optional) — имя параметра
     * для bind'a; {@code parameterListAllowed} — пишется только если {@code parameter} есть.
     *
     * <p>Идемпотентно по паре {@code (sourceDataSet, destinationDataSet, sourceExpression,
     * destinationExpression)} — если такая связь уже есть, no-op.
     */
    public boolean addDataSetLink(IFile dcsFile, String source, String destination,
                                   String sourceExpression, String destinationExpression,
                                   String parameter) throws ToolException {
        if (source == null || source.isEmpty()) throw new ToolException("source required");
        if (destination == null || destination.isEmpty()) throw new ToolException("destination required");
        if (sourceExpression == null || sourceExpression.isEmpty())
            throw new ToolException("sourceExpression required");
        if (destinationExpression == null || destinationExpression.isEmpty())
            throw new ToolException("destinationExpression required");
        Document doc = load(dcsFile);
        boolean changed = applyAddDataSetLink(doc, source, destination, sourceExpression,
                                              destinationExpression, parameter);
        if (changed) save(dcsFile, doc);
        return changed;
    }

    /** Pure-DOM helper для {@link #addDataSetLink}. */
    public static boolean applyAddDataSetLink(Document doc, String source, String destination,
                                               String sourceExpression, String destinationExpression,
                                               String parameter) throws ToolException {
        Element root = doc.getDocumentElement();
        if (root == null) throw new ToolException("empty .dcs document");

        // Validate that source + destination dataSet'ы существуют
        if (findChildByNameTag(root, "dataSet", source) == null) {
            throw new ToolException("source dataSet '" + source + "' not found in .dcs");
        }
        if (findChildByNameTag(root, "dataSet", destination) == null) {
            throw new ToolException("destination dataSet '" + destination + "' not found in .dcs");
        }

        // Dup check по (source, destination, sourceExpr, destExpr)
        NodeList links = root.getElementsByTagName("dataSetLink");
        for (int i = 0; i < links.getLength(); i++) {
            Element l = (Element) links.item(i);
            if (l.getParentNode() != root) continue;
            if (source.equals(childTextStr(l, "sourceDataSet"))
                    && destination.equals(childTextStr(l, "destinationDataSet"))
                    && sourceExpression.equals(childTextStr(l, "sourceExpression"))
                    && destinationExpression.equals(childTextStr(l, "destinationExpression"))) {
                return false;
            }
        }

        Element link = doc.createElement("dataSetLink");
        appendText(doc, link, "sourceDataSet",         source);
        appendText(doc, link, "destinationDataSet",    destination);
        appendText(doc, link, "sourceExpression",      sourceExpression);
        appendText(doc, link, "destinationExpression", destinationExpression);
        if (parameter != null && !parameter.isEmpty()) {
            appendText(doc, link, "parameter",            parameter);
            appendText(doc, link, "parameterListAllowed", "false");
        }

        // Канон: dataSetLink идёт после dataSet'ов, перед calculatedField/totalField/parameter/settingsVariant.
        Node anchor = firstChildByTag(root, "calculatedField");
        if (anchor == null) anchor = firstChildByTag(root, "totalField");
        if (anchor == null) anchor = firstChildByTag(root, "parameter");
        if (anchor == null) anchor = firstChildByTag(root, "settingsVariant");
        if (anchor != null) root.insertBefore(link, anchor);
        else                root.appendChild(link);
        return true;
    }

    private static String childTextStr(Element parent, String tag) {
        Element e = firstChildByTag(parent, tag);
        return e != null ? e.getTextContent() : null;
    }

    // =========================================================================
    // Stage 8g (2026-05-31): settingsVariant editing —
    // add_dcs_setting_grouping / add_dcs_setting_filter / set_dcs_setting_parameter_value.
    //
    // Все операции работают внутри <settingsVariant>/<dcsset:settings>:
    //  - grouping: добавить <dcsset:item xsi:type="dcsset:GroupItemField"> в
    //      <dcsset:groupItems> корневого <dcsset:item xsi:type="dcsset:StructureItemGroup">.
    //  - filter: добавить <dcsset:item xsi:type="dcsset:FilterItemComparison"> в
    //      <dcsset:filter>; <dcsset:filter> создаётся если отсутствует.
    //  - parameter_value: добавить/обновить <dcscor:item xsi:type="dcsset:SettingsParameterValue">
    //      в <dcsset:dataParameters>; контейнер создаётся если отсутствует.
    // =========================================================================

    /**
     * Добавить группировку (поле с типом группировки) в root StructureItemGroup
     * settingsVariant'а.
     *
     * <p>Пример: {@code add_dcs_setting_grouping(file, "Основной", "Номенклатура", "Items")}
     * добавит группировку по полю Номенклатура с типом Items внутрь корневой группы
     * настроек варианта.
     *
     * @param variantName имя settingsVariant'а ({@code <dcsset:name>X</dcsset:name>})
     * @param field       имя поля DCS (e.g. "Номенклатура")
     * @param groupType   тип группировки: Items | Hierarchy | HierarchyOnly (default Items)
     * @return {@code true} если добавлено, {@code false} если такое поле уже сгруппировано
     */
    public boolean addSettingsGrouping(IFile dcsFile, String variantName, String field,
                                       String groupType) throws ToolException {
        if (field == null || field.isEmpty()) throw new ToolException("field required");
        Document doc = load(dcsFile);
        boolean changed = applyAddSettingsGrouping(doc, variantName, field, groupType);
        if (changed) save(dcsFile, doc);
        return changed;
    }

    /** Pure-DOM helper для {@link #addSettingsGrouping}. */
    public static boolean applyAddSettingsGrouping(Document doc, String variantName,
                                                    String field, String groupType)
            throws ToolException {
        Element settings = findSettings(doc, variantName);
        Element structureGroup = findStructureItemGroup(settings);
        if (structureGroup == null) {
            throw new ToolException("settingsVariant '" + variantName + "' has no root StructureItemGroup");
        }

        Element groupItems = firstChildByTag(structureGroup, "dcsset:groupItems");
        if (groupItems == null) {
            groupItems = doc.createElement("dcsset:groupItems");
            // Insert before <dcsset:order>/<dcsset:selection>/<dcsset:itemsViewMode>
            Node anchor = firstChildByTag(structureGroup, "dcsset:order");
            if (anchor == null) anchor = firstChildByTag(structureGroup, "dcsset:selection");
            if (anchor == null) anchor = firstChildByTag(structureGroup, "dcsset:itemsViewMode");
            if (anchor != null) structureGroup.insertBefore(groupItems, anchor);
            else                structureGroup.appendChild(groupItems);
        }

        // Dup check: уже есть GroupItemField с таким <dcsset:field>?
        NodeList items = groupItems.getChildNodes();
        for (int i = 0; i < items.getLength(); i++) {
            Node n = items.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"dcsset:item".equals(n.getNodeName())) continue;
            Element it = (Element) n;
            if (!"dcsset:GroupItemField".equals(it.getAttribute("xsi:type"))) continue;
            if (field.equals(childTextStr(it, "dcsset:field"))) return false;
        }

        String effectiveType = (groupType == null || groupType.isEmpty()) ? "Items" : groupType;

        Element item = doc.createElement("dcsset:item");
        item.setAttribute("xsi:type", "dcsset:GroupItemField");
        appendText(doc, item, "dcsset:field",     field);
        appendText(doc, item, "dcsset:groupType", effectiveType);
        // Канонический контракт: periodAdditionType + начальные даты-нулевые
        appendText(doc, item, "dcsset:periodAdditionType", "None");
        Element begin = doc.createElement("dcsset:periodAdditionBegin");
        begin.setAttribute("xsi:type", "xs:dateTime");
        begin.setTextContent("0001-01-01T00:00:00");
        item.appendChild(begin);
        Element end = doc.createElement("dcsset:periodAdditionEnd");
        end.setAttribute("xsi:type", "xs:dateTime");
        end.setTextContent("0001-01-01T00:00:00");
        item.appendChild(end);

        groupItems.appendChild(item);
        return true;
    }

    /**
     * Добавить filter-условие {@code FilterItemComparison} в settingsVariant.
     *
     * <p>{@code <dcsset:filter>} создаётся при первом filter'е. Дубль (по left+comparisonType)
     * — no-op.
     *
     * @param variantName     имя settingsVariant'а
     * @param leftField       выражение слева (поле DCS, e.g. "Номенклатура.ВидНоменклатуры")
     * @param comparisonType  тип сравнения: Equal | NotEqual | Greater | Less | Contains | ...
     * @param use             начальное значение {@code <use>} (default false — отключён до явного use)
     */
    public boolean addSettingsFilter(IFile dcsFile, String variantName, String leftField,
                                     String comparisonType, boolean use) throws ToolException {
        if (leftField == null || leftField.isEmpty()) throw new ToolException("leftField required");
        if (comparisonType == null || comparisonType.isEmpty()) {
            throw new ToolException("comparisonType required (Equal|NotEqual|Greater|Less|...)");
        }
        Document doc = load(dcsFile);
        boolean changed = applyAddSettingsFilter(doc, variantName, leftField, comparisonType, use);
        if (changed) save(dcsFile, doc);
        return changed;
    }

    /** Pure-DOM helper для {@link #addSettingsFilter}. */
    public static boolean applyAddSettingsFilter(Document doc, String variantName, String leftField,
                                                 String comparisonType, boolean use)
            throws ToolException {
        Element settings = findSettings(doc, variantName);

        Element filter = firstChildByTag(settings, "dcsset:filter");
        if (filter == null) {
            filter = doc.createElement("dcsset:filter");
            // dcsset:filter идёт после dataParameters/selection, перед StructureItemGroup
            Node anchor = findStructureItemGroup(settings);
            if (anchor != null) settings.insertBefore(filter, anchor);
            else                settings.appendChild(filter);
        }

        // Dup check
        NodeList items = filter.getChildNodes();
        for (int i = 0; i < items.getLength(); i++) {
            Node n = items.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"dcsset:item".equals(n.getNodeName())) continue;
            Element it = (Element) n;
            if (!"dcsset:FilterItemComparison".equals(it.getAttribute("xsi:type"))) continue;
            String lf = childTextStr(it, "dcsset:left");
            String ct = childTextStr(it, "dcsset:comparisonType");
            if (leftField.equals(lf) && comparisonType.equals(ct)) return false;
        }

        Element item = doc.createElement("dcsset:item");
        item.setAttribute("xsi:type", "dcsset:FilterItemComparison");
        appendText(doc, item, "dcsset:use", use ? "true" : "false");
        Element left = doc.createElement("dcsset:left");
        left.setAttribute("xsi:type", "dcscor:Field");
        left.setTextContent(leftField);
        item.appendChild(left);
        appendText(doc, item, "dcsset:comparisonType", comparisonType);

        filter.appendChild(item);
        return true;
    }

    /**
     * Установить значение параметра в settingsVariant'а. Replace-or-add семантика:
     * если SettingsParameterValue с таким parameter name уже есть — обновляем
     * {@code <dcscor:value>}; иначе создаём новый.
     *
     * @param variantName   имя settingsVariant'а
     * @param parameterName имя параметра (must match an existing root {@code <parameter>})
     * @param value         текстовое значение; {@code null} → {@code xsi:nil="true"}
     */
    public boolean setSettingsParameterValue(IFile dcsFile, String variantName, String parameterName,
                                              String value) throws ToolException {
        if (parameterName == null || parameterName.isEmpty()) {
            throw new ToolException("parameterName required");
        }
        Document doc = load(dcsFile);
        boolean changed = applySetSettingsParameterValue(doc, variantName, parameterName, value);
        if (changed) save(dcsFile, doc);
        return changed;
    }

    /** Pure-DOM helper для {@link #setSettingsParameterValue}. */
    public static boolean applySetSettingsParameterValue(Document doc, String variantName,
                                                          String parameterName, String value)
            throws ToolException {
        Element settings = findSettings(doc, variantName);

        Element dataParameters = firstChildByTag(settings, "dcsset:dataParameters");
        if (dataParameters == null) {
            dataParameters = doc.createElement("dcsset:dataParameters");
            // Канонически dataParameters идёт первым в <dcsset:settings>
            Node first = settings.getFirstChild();
            if (first != null) settings.insertBefore(dataParameters, first);
            else               settings.appendChild(dataParameters);
        }

        // Replace-or-add: найти существующий item с этим параметром
        NodeList items = dataParameters.getChildNodes();
        for (int i = 0; i < items.getLength(); i++) {
            Node n = items.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"dcscor:item".equals(n.getNodeName())) continue;
            Element it = (Element) n;
            if (!"dcsset:SettingsParameterValue".equals(it.getAttribute("xsi:type"))) continue;
            String pName = childTextStr(it, "dcscor:parameter");
            if (!parameterName.equals(pName)) continue;
            // Update existing value
            Element existingVal = firstChildByTag(it, "dcscor:value");
            if (existingVal != null) it.removeChild(existingVal);
            it.appendChild(buildValue(doc, value));
            return true;
        }

        // Add new
        Element item = doc.createElement("dcscor:item");
        item.setAttribute("xsi:type", "dcsset:SettingsParameterValue");
        appendText(doc, item, "dcscor:parameter", parameterName);
        item.appendChild(buildValue(doc, value));
        dataParameters.appendChild(item);
        return true;
    }

    private static Element buildValue(Document doc, String value) {
        Element v = doc.createElement("dcscor:value");
        if (value == null) {
            v.setAttribute("xsi:nil", "true");
        } else {
            v.setTextContent(value);
        }
        return v;
    }

    /** Находит {@code <dcsset:settings>} внутри settingsVariant с именем variantName. */
    private static Element findSettings(Document doc, String variantName) throws ToolException {
        Element root = doc.getDocumentElement();
        if (root == null) throw new ToolException("empty .dcs document");
        if (variantName == null || variantName.isEmpty()) {
            throw new ToolException("variantName required");
        }
        NodeList variants = root.getElementsByTagName("settingsVariant");
        for (int i = 0; i < variants.getLength(); i++) {
            Element v = (Element) variants.item(i);
            if (v.getParentNode() != root) continue;
            if (variantName.equals(childTextStr(v, "dcsset:name"))) {
                Element s = firstChildByTag(v, "dcsset:settings");
                if (s == null) {
                    throw new ToolException("settingsVariant '" + variantName + "' has no <dcsset:settings>");
                }
                return s;
            }
        }
        throw new ToolException("settingsVariant '" + variantName + "' not found in .dcs");
    }

    /** Находит корневой {@code <dcsset:item xsi:type="dcsset:StructureItemGroup">} внутри settings. */
    private static Element findStructureItemGroup(Element settings) {
        NodeList kids = settings.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"dcsset:item".equals(n.getNodeName())) continue;
            Element it = (Element) n;
            if ("dcsset:StructureItemGroup".equals(it.getAttribute("xsi:type"))) return it;
        }
        return null;
    }

    /** Найти элемент {@code <tag>} (direct child) с {@code <dataPath>X</dataPath>}. */
    private static Element findChildByDataPathTag(Element parent, String tag, String dataPath) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE || !tag.equals(k.getNodeName())) continue;
            Element e = (Element) k;
            Element dp = firstChildByTag(e, "dataPath");
            if (dp != null && dataPath.equals(dp.getTextContent())) return e;
        }
        return null;
    }

    // -------------------------------------------------------------------------

    /** Build {@code <title xsi:type="v8:LocalStringType"><v8:item><v8:lang>ru</v8:lang><v8:content>X</v8:content></v8:item></title>}. */
    private static Element buildTitle(Document doc, String title) {
        Element t = doc.createElement("title");
        t.setAttribute("xsi:type", "v8:LocalStringType");
        Element item = doc.createElement("v8:item");
        appendText(doc, item, "v8:lang",    "ru");
        appendText(doc, item, "v8:content", title);
        t.appendChild(item);
        return t;
    }

    /**
     * Найти дочерний {@code <tag>} (direct child of {@code parent}), у которого
     * {@code <name>} равно {@code name}.
     */
    private static Element findChildByNameTag(Element parent, String tag, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE || !tag.equals(k.getNodeName())) continue;
            Element e = (Element) k;
            if (name.equals(childText(e, "name"))) return e;
        }
        return null;
    }

    private static Element firstChildByTag(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getNodeName())) return (Element) k;
        }
        return null;
    }

    private static String childText(Element parent, String tag) {
        Element e = firstChildByTag(parent, tag);
        return e != null ? e.getTextContent() : null;
    }

    private static void appendText(Document doc, Element parent, String tag, String text) {
        Element e = doc.createElement(tag);
        e.setTextContent(text);
        parent.appendChild(e);
    }

    // --- IO ---

    private static Document load(IFile file) throws ToolException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // .dcs имеет default xmlns="http://v8.1c.ru/8.1/data-composition-system/schema".
            // namespaceAware=false → лексический разбор, createElement без NS работает чисто.
            dbf.setNamespaceAware(false);
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
