package ru.fedukhin.edt.mcp.tools.md.internal;

/**
 * Описание поддерживаемого Stage 5 типа MdObject:
 *  - {@code name} — короткое имя ("Catalog", "Document", "CommonModule", …);
 *  - {@code containerFeatureName} — accessor на Configuration ("getCatalogs", "getDocuments", …);
 *  - {@code folderName} — папка в {@code src/} ("Catalogs", "Documents", "AccumulationRegisters", …)
 *    или {@code null}, если kind не материализуется в виде {@code src/<Folder>/<Name>/}.
 *  - {@code supportsAttributes} — есть ли у объекта коллекция Attribute/Dimension/Resource для редактирования;
 *  - {@code hasModule} / {@code moduleFileName} — есть ли «дефолтный» .bsl-модуль и как он называется
 *    (Module.bsl для CommonModule, ObjectModule.bsl для Document/Catalog/DataProcessor/Report,
 *    RecordSetModule.bsl для регистров).
 *  - {@code requireEmptyModuleFile} — создавать ли при {@code create_md_object} пустой .bsl физически
 *    на диске. {@code true} только для CommonModule (без него EDT не распознаёт модуль).
 *    Для остальных — {@code false}: {@code create_md_object} вернёт путь, но файл создаст
 *    модель агента (например, через {@code add_extension_method_override}) при первом write'е.
 *
 * Конкретные EClass / EFactory для каждого kind подключаются в {@link MdObjectFactory}
 * (Plan 2) по {@code name} — registry хранит только метаданные про API-форму.
 */
public final class MdObjectKind {
    private final String  name;
    private final String  containerFeatureName;
    private final String  folderName;
    private final boolean supportsAttributes;
    private final boolean hasModule;
    private final String  moduleFileName;
    private final boolean requireEmptyModuleFile;

    MdObjectKind(String name, String containerFeatureName, String folderName,
                 boolean supportsAttributes, boolean hasModule,
                 String moduleFileName, boolean requireEmptyModuleFile) {
        this.name                   = name;
        this.containerFeatureName   = containerFeatureName;
        this.folderName             = folderName;
        this.supportsAttributes     = supportsAttributes;
        this.hasModule              = hasModule;
        this.moduleFileName         = moduleFileName;
        this.requireEmptyModuleFile = requireEmptyModuleFile;
    }

    public String  name()                   { return name; }
    public String  containerFeatureName()   { return containerFeatureName; }
    public String  folderName()             { return folderName; }
    public boolean supportsAttributes()     { return supportsAttributes; }
    public boolean hasModule()              { return hasModule; }
    public String  moduleFileName()         { return moduleFileName; }
    public boolean requireEmptyModuleFile() { return requireEmptyModuleFile; }
}
