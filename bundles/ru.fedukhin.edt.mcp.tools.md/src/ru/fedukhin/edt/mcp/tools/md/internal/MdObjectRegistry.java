package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable реестр 11 поддерживаемых v1-типов редактируемых MdObject:
 * Catalog, Document, InformationRegister, AccumulationRegister (с attributes/dimensions/resources);
 * Constant, Enum, CommonModule, Role, Subsystem, DataProcessor, Report (без editable attributes).
 *
 * Configuration root в реестр НЕ входит — он не редактируется через create_md_object, и
 * lookup идёт по литералу "Configuration" в {@code MdObjectLocator}.
 *
 * Точка единственности по kind-у — tools НЕ содержат switch по name.
 */
public final class MdObjectRegistry {

    private final Map<String, MdObjectKind> kinds;

    public MdObjectRegistry() {
        Map<String, MdObjectKind> m = new LinkedHashMap<>();
        // С attributes. modulePath для data-kinds — это «дефолтный» .bsl-модуль,
        // в который вешаются event handlers (ObjectModule.bsl у Catalog/Document/DataProcessor/Report,
        // RecordSetModule.bsl у Register-kinds). Файл физически не создаётся
        // на этапе create_md_object — путь возвращается, чтобы модель агента
        // знала, куда писать (например, через add_extension_method_override).
        m.put("Catalog",
                new MdObjectKind("Catalog", "getCatalogs", "Catalogs",
                        true, true, "ObjectModule.bsl", false));
        m.put("Document",
                new MdObjectKind("Document", "getDocuments", "Documents",
                        true, true, "ObjectModule.bsl", false));
        m.put("InformationRegister",
                new MdObjectKind("InformationRegister", "getInformationRegisters", "InformationRegisters",
                        true, true, "RecordSetModule.bsl", false));
        m.put("AccumulationRegister",
                new MdObjectKind("AccumulationRegister", "getAccumulationRegisters", "AccumulationRegisters",
                        true, true, "RecordSetModule.bsl", false));
        // Без attributes
        m.put("Constant",
                new MdObjectKind("Constant", "getConstants", "Constants",
                        false, false, null, false));
        m.put("Enum",
                new MdObjectKind("Enum", "getEnums", "Enums",
                        false, false, null, false));
        // CommonModule — модуль = вся суть объекта; пустой Module.bsl создаётся
        // физически (без него EDT не распознаёт модуль).
        m.put("CommonModule",
                new MdObjectKind("CommonModule", "getCommonModules", "CommonModules",
                        false, true, "Module.bsl", true));
        m.put("Role",
                new MdObjectKind("Role", "getRoles", "Roles",
                        false, false, null, false));
        m.put("Subsystem",
                new MdObjectKind("Subsystem", "getSubsystems", "Subsystems",
                        false, false, null, false));
        m.put("DataProcessor",
                new MdObjectKind("DataProcessor", "getDataProcessors", "DataProcessors",
                        false, true, "ObjectModule.bsl", false));
        m.put("Report",
                new MdObjectKind("Report", "getReports", "Reports",
                        false, true, "ObjectModule.bsl", false));
        this.kinds = java.util.Collections.unmodifiableMap(m);
    }

    public MdObjectKind get(String name) {
        return kinds.get(name);
    }

    public Collection<MdObjectKind> allKinds() {
        return kinds.values();
    }
}
