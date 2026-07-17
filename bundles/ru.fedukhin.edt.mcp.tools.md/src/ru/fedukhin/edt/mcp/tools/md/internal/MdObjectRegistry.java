package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable реестр поддерживаемых типов MdObject — единая точка правды по kind'ам.
 *
 * <p>Реестр покрывает <em>все</em> kind'ы, которые умеет заимствовать {@link MdObjectBorrower}.
 * Раньше он знал только 11 из них, и это был корень целого класса багов: заимствованные
 * планы счетов и видов расчёта, Task, BusinessProcess, ExchangePlan, DocumentJournal, CommonForm
 * и CommonPicture были невидимы для {@code list_md_objects} (итерация идёт по реестру) и
 * недоступны {@code get_md_object} («unknown kind»), а в {@code add_attribute} пять kind'ов были
 * мертвы — их отбивало на {@code registry.get(kind) == null} ещё до folder-маппинга.
 *
 * <p>{@code creatable} отделяет «можно создать с нуля» от «можно прочитать и заимствовать»:
 * не-creatable остались только kind'ы, которым нужны сопутствующие артефакты, которые
 * {@code create_md_object} не создаёт (CommonForm — Form.form, CommonPicture — файл картинки).
 *
 * <p>Configuration root в реестр НЕ входит — он не редактируется через {@code create_md_object},
 * и lookup идёт по литералу "Configuration" в {@code MdObjectLocator}.
 */
public final class MdObjectRegistry {

    private static final Map<String, MdObjectKind> KINDS = buildKinds();

    private final Map<String, MdObjectKind> kinds = KINDS;

    private static Map<String, MdObjectKind> buildKinds() {
        Map<String, MdObjectKind> m = new LinkedHashMap<>();
        // С attributes. modulePath для data-kinds — это «дефолтный» .bsl-модуль,
        // в который вешаются event handlers (ObjectModule.bsl у Catalog/Document/DataProcessor/Report,
        // RecordSetModule.bsl у Register-kinds). Файл физически не создаётся
        // на этапе create_md_object — путь возвращается, чтобы модель агента
        // знала, куда писать (например, через add_extension_method_override).
        m.put("Catalog",
                new MdObjectKind("Catalog", "getCatalogs", "Catalogs",
                        true, true, "ObjectModule.bsl", false, true));
        m.put("Document",
                new MdObjectKind("Document", "getDocuments", "Documents",
                        true, true, "ObjectModule.bsl", false, true));
        m.put("InformationRegister",
                new MdObjectKind("InformationRegister", "getInformationRegisters", "InformationRegisters",
                        true, true, "RecordSetModule.bsl", false, true));
        m.put("AccumulationRegister",
                new MdObjectKind("AccumulationRegister", "getAccumulationRegisters", "AccumulationRegisters",
                        true, true, "RecordSetModule.bsl", false, true));
        // Без attributes
        m.put("Constant",
                new MdObjectKind("Constant", "getConstants", "Constants",
                        false, false, null, false, true));
        m.put("Enum",
                new MdObjectKind("Enum", "getEnums", "Enums",
                        false, false, null, false, true));
        // CommonModule — модуль = вся суть объекта; пустой Module.bsl создаётся
        // физически (без него EDT не распознаёт модуль).
        m.put("CommonModule",
                new MdObjectKind("CommonModule", "getCommonModules", "CommonModules",
                        false, true, "Module.bsl", true, true));
        m.put("Role",
                new MdObjectKind("Role", "getRoles", "Roles",
                        false, false, null, false, true));
        m.put("Subsystem",
                new MdObjectKind("Subsystem", "getSubsystems", "Subsystems",
                        false, false, null, false, true));
        m.put("DataProcessor",
                new MdObjectKind("DataProcessor", "getDataProcessors", "DataProcessors",
                        false, true, "ObjectModule.bsl", false, true));
        m.put("Report",
                new MdObjectKind("Report", "getReports", "Reports",
                        false, true, "ObjectModule.bsl", false, true));

        // Data-kinds «второй волны» — creatable с 1.20.0: EMF-фабрика + BM-сериализация дают
        // валидный минимальный .mdo (дефолты EMF-модели совпадают с умолчаниями Designer),
        // сопутствующие артефакты им не нужны. Проверяются live-smoke'ом на каждом релизе.
        // У этих есть getAttributes()/getTabularSections(), поэтому add_attribute и
        // add_tabular_section работают и на созданном, и на заимствованном объекте.
        m.put("BusinessProcess",
                new MdObjectKind("BusinessProcess", "getBusinessProcesses", "BusinessProcesses",
                        true, true, "ObjectModule.bsl", false, true));
        m.put("Task",
                new MdObjectKind("Task", "getTasks", "Tasks",
                        true, true, "ObjectModule.bsl", false, true));
        m.put("ChartOfAccounts",
                new MdObjectKind("ChartOfAccounts", "getChartsOfAccounts", "ChartsOfAccounts",
                        true, true, "ObjectModule.bsl", false, true));
        m.put("ChartOfCalculationTypes",
                new MdObjectKind("ChartOfCalculationTypes", "getChartsOfCalculationTypes",
                        "ChartsOfCalculationTypes",
                        true, true, "ObjectModule.bsl", false, true));
        m.put("ChartOfCharacteristicTypes",
                new MdObjectKind("ChartOfCharacteristicTypes", "getChartsOfCharacteristicTypes",
                        "ChartsOfCharacteristicTypes",
                        true, true, "ObjectModule.bsl", false, true));
        m.put("ExchangePlan",
                new MdObjectKind("ExchangePlan", "getExchangePlans", "ExchangePlans",
                        true, true, "ObjectModule.bsl", false, true));
        // DocumentJournal несёт графы и колонки, а не реквизиты.
        m.put("DocumentJournal",
                new MdObjectKind("DocumentJournal", "getDocumentJournals", "DocumentJournals",
                        false, false, null, false, true));
        // Заимствуемые, но не создаваемые (creatable=false): CommonForm без Form.form и
        // CommonPicture без файла картинки бессмысленны, а create_md_object сопутствующие
        // артефакты не создаёт.
        m.put("CommonForm",
                new MdObjectKind("CommonForm", "getCommonForms", "CommonForms",
                        false, true, "Module.bsl", false, false));
        m.put("CommonPicture",
                new MdObjectKind("CommonPicture", "getCommonPictures", "CommonPictures",
                        false, false, null, false, false));
        return java.util.Collections.unmodifiableMap(m);
    }

    public MdObjectKind get(String name) {
        return kinds.get(name);
    }

    public Collection<MdObjectKind> allKinds() {
        return kinds.values();
    }

    /**
     * Имя папки в {@code src/} для kind'а — единая точка правды для всех инструментов.
     *
     * <p>Раньше маппинг был скопирован в каждый инструмент (add_attribute, add_tabular_section,
     * add_md_template, list_attributes, borrow_form, get_form, …), и копии разъезжались: это и
     * было корнем находки Task 6. Отдельно отличился {@code GetFormTool.pluralize} с наивным
     * {@code kind + "s"} — он давал несуществующие «BusinessProcesss» и «ChartOfAccountss».
     *
     * <p>Статический метод (а не только инстанс-{@link #get}) — чтобы им могли пользоваться
     * инструменты без инъекции реестра, не заводя новую копию карты.
     *
     * @return имя папки либо {@code null}, если kind неизвестен или не материализуется в src/
     */
    public static String folderName(String kind) {
        MdObjectKind k = KINDS.get(kind);
        return k == null ? null : k.folderName();
    }
}
