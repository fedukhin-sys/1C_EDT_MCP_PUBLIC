package ru.fedukhin.edt.mcp.tools.md;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectBorrower;

/**
 * {@code borrow_md_object} — заимствование (adopt) MdObject'а из base configuration
 * в extension (Stage 8d).
 *
 * <p>Args: {@code { project, fqn }}
 * <ul>
 *   <li>{@code project} — имя <b>extension</b> проекта (e.g. "ЕСС.РасширениеТЕСТ").</li>
 *   <li>{@code fqn} — FQN объекта в base configuration ({@code Catalog.Номенклатура},
 *       {@code Document.ЗаказКлиента}, {@code Enum.ВариантыКурса}, и т.п.).</li>
 * </ul>
 *
 * <p>Result: {@code { project, fqn, adoptedUuid, baseUuid, mdoPath }}
 *
 * <p>Supported kinds: Catalog, Document, Enum, InformationRegister,
 * AccumulationRegister, DataProcessor, Report, BusinessProcess, Task,
 * ChartOfAccounts, ChartOfCalculationTypes, ChartOfCharacteristicTypes,
 * ExchangePlan, DocumentJournal, CommonModule, CommonForm, CommonPicture,
 * Constant, Subsystem.
 *
 * <p>Что делает:
 * <ol>
 *   <li>Resolve base configuration через {@code IExtensionProject.getParent()}.</li>
 *   <li>Читает base {@code .mdo}, регенерирует {@code producedTypes} UUIDs.</li>
 *   <li>Пишет adopted {@code .mdo} с {@code objectBelonging=Adopted} + {@code extension} block.</li>
 *   <li>Добавляет ссылку в extension's {@code Configuration.mdo}.</li>
 *   <li>BM sync через {@code waitModelSynchronization}.</li>
 * </ol>
 *
 * <p>Sub-borrow (Form/TabularSection/EnumValue из base) — отдельный механизм,
 * не покрывается в первой итерации.
 */
public final class BorrowMdObjectTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MdObjectBorrower         borrower;

    @Inject
    public BorrowMdObjectTool(MdObjectBorrower borrower) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), borrower);
    }

    /** Test seam. */
    public BorrowMdObjectTool(Supplier<IWorkspaceRoot> rootSupplier, MdObjectBorrower borrower) {
        this.rootSupplier = rootSupplier;
        this.borrower     = borrower;
    }

    @Override public String name()        { return "borrow_md_object"; }
    @Override public String description() {
        return "Adopt (borrow) an MdObject from base configuration into an extension. "
             + "project = extension name; fqn = '<Kind>.<Name>' of the base object.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", str);
        props.put("fqn",     str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "fqn"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String fqn         = requireString(args, "fqn");

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        MdObjectBorrower.BorrowResult res = borrower.borrow(project, fqn);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project",     res.project());
        result.put("fqn",         res.fqn());
        result.put("adoptedUuid", res.adoptedUuid());
        result.put("baseUuid",    res.baseUuid());
        result.put("mdoPath",     res.mdoPath());
        if (res.cascadedOwners() != null && !res.cascadedOwners().isEmpty()) {
            result.put("cascadedOwners", res.cascadedOwners());
        }
        return result;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
