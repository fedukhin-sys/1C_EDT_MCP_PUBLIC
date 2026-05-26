package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Создаёт Attribute / Dimension / Resource на parent MdObject.
 *
 * <p>Маппинг (kind, role) → (parent-accessor, MdClassFactory.create*):
 * <ul>
 *   <li>Catalog + Attribute → getAttributes / createCatalogAttribute</li>
 *   <li>Document + Attribute → getAttributes / createDocumentAttribute</li>
 *   <li>InformationRegister + Attribute|Dimension|Resource → getAttributes/getDimensions/getResources
 *       / createInformationRegisterAttribute|...Dimension|...Resource</li>
 *   <li>AccumulationRegister + Attribute|Dimension|Resource → аналогично</li>
 * </ul>
 *
 * <p>Прочие kind'ы (Constant, Enum, CommonModule, Role, Subsystem, DataProcessor, Report) —
 * запрещены вызовом, ToolException. Caller (AddAttributeTool) проверяет через
 * {@link MdObjectRegistry}/{@code supportsAttributes()} раньше.
 *
 * <p>TypeDescription строится через McoreFactory (Spike 1). Qualifiers:
 * <ul>
 *   <li>STRING → {@link StringQualifiers#setLength(int)}</li>
 *   <li>NUMBER → {@link NumberQualifiers#setPrecision(int)} (total digits),
 *       {@link NumberQualifiers#setScale(int)} (fraction digits).
 *       Note: API uses setPrecision/setScale — NOT setDigits/setFractionDigits
 *       (verified via javap on com._1c.g5.v8.dt.mcore).</li>
 * </ul>
 */
public final class AttributeFactory {

    @Inject
    public AttributeFactory() {}

    /**
     * Добавляет child-attribute на parent и возвращает созданный EObject.
     *
     * @param parent   MdObject (Catalog, Document, InformationRegister, AccumulationRegister)
     * @param kind     строковый kind parent'а ("Catalog", "Document", ...)
     * @param role     роль дочернего объекта: "Attribute" | "Dimension" | "Resource" | null
     * @param name     имя нового дочернего объекта
     * @param types    список parsed-типов для TypeDescription
     * @param synonym  (unused here — set_md_property responsibility) — передаётся для полноты
     * @param comment  комментарий (optional)
     * @throws ToolException если kind не поддерживается, role неверна, или имя уже занято
     */
    public EObject add(EObject parent, String kind, String role,
                       String name, List<ParsedType> types,
                       String synonym, String comment) throws ToolException {
        ResolvedRole rr = resolveRole(kind, role);

        @SuppressWarnings("unchecked")
        List<EObject> list = (List<EObject>) invokeAccessor(parent, rr.accessor);

        // Проверка уникальности имени
        for (EObject existing : list) {
            try {
                Object n = existing.getClass().getMethod("getName").invoke(existing);
                if (name.equals(n)) {
                    throw new ToolException("attribute '" + name + "' already exists on parent (role="
                            + rr.role + ")");
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("getName() missing on " + existing.getClass(), e);
            }
        }

        EObject child = createChild(rr.factoryMethod);
        invokeSet(child, "setName", String.class, name);
        // setUuid обязателен для child-MdObject — иначе deploy_project падает с
        // ExportException «uuid is null» (live smoke 2026-05-17 на Stage 8a v6
        // показал, что bug идентичный для форм и для атрибутов).
        invokeSet(child, "setUuid", java.util.UUID.class, java.util.UUID.randomUUID());
        if (comment != null) {
            invokeSetIfAvailable(child, "setComment", String.class, comment);
        }
        TypeDescription td = buildTypeDescription(types);
        invokeSet(child, "setType", TypeDescription.class, td);
        list.add(child);
        return child;
    }

    // ---- Внутренние типы и resolve ----

    private static final class ResolvedRole {
        final String role;
        final String accessor;
        final String factoryMethod;

        ResolvedRole(String role, String accessor, String factoryMethod) {
            this.role = role;
            this.accessor = accessor;
            this.factoryMethod = factoryMethod;
        }
    }

    private static ResolvedRole resolveRole(String kind, String role) throws ToolException {
        switch (kind) {
            case "Catalog":
                if (role == null || "Attribute".equals(role)) {
                    return new ResolvedRole("Attribute", "getAttributes", "createCatalogAttribute");
                }
                throw new ToolException("kind " + kind + " supports only role=Attribute");

            case "Document":
                if (role == null || "Attribute".equals(role)) {
                    return new ResolvedRole("Attribute", "getAttributes", "createDocumentAttribute");
                }
                throw new ToolException("kind " + kind + " supports only role=Attribute");

            case "InformationRegister":
                return resolveRegisterRole(role, kind, "InformationRegister");

            case "AccumulationRegister":
                return resolveRegisterRole(role, kind, "AccumulationRegister");

            default:
                throw new ToolException("md object kind '" + kind + "' does not support attributes");
        }
    }

    private static ResolvedRole resolveRegisterRole(String role, String kind, String regPrefix)
            throws ToolException {
        if (role == null) {
            throw new ToolException(
                    "register kinds require 'role' parameter: \"Attribute\" | \"Dimension\" | \"Resource\"");
        }
        switch (role) {
            case "Attribute":
                return new ResolvedRole("Attribute", "getAttributes", "create" + regPrefix + "Attribute");
            case "Dimension":
                return new ResolvedRole("Dimension", "getDimensions", "create" + regPrefix + "Dimension");
            case "Resource":
                return new ResolvedRole("Resource", "getResources", "create" + regPrefix + "Resource");
            default:
                throw new ToolException("unknown role '" + role + "' for kind " + kind);
        }
    }

    // ---- Фабрика через MdClassFactory ----

    private static EObject createChild(String factoryMethod) throws ToolException {
        try {
            return (EObject) MdClassFactory.eINSTANCE.getClass()
                    .getMethod(factoryMethod)
                    .invoke(MdClassFactory.eINSTANCE);
        } catch (ReflectiveOperationException e) {
            throw new ToolException("internal: cannot invoke MdClassFactory." + factoryMethod);
        }
    }

    // ---- Reflection helpers ----

    private static Object invokeAccessor(EObject obj, String accessor) {
        try {
            return obj.getClass().getMethod(accessor).invoke(obj);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("accessor " + accessor + " missing on " + obj.getClass(), e);
        }
    }

    private static void invokeSet(EObject child, String method, Class<?> paramType, Object value) {
        try {
            child.getClass().getMethod(method, paramType).invoke(child, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(method + " missing on " + child.getClass(), e);
        }
    }

    private static void invokeSetIfAvailable(EObject child, String method, Class<?> paramType, Object value) {
        try {
            child.getClass().getMethod(method, paramType).invoke(child, value);
        } catch (ReflectiveOperationException ignored) {
            // метод опционален — пропускаем
        }
    }

    // ---- TypeDescription builder ----

    /**
     * Конвертация ParsedType[] → TypeDescription via McoreFactory.
     *
     * <p>API-адаптации (verified via javap):
     * <ul>
     *   <li>NumberQualifiers: {@code setPrecision(int)} = total digit count (аналог «длина»),
     *       {@code setScale(int)} = fraction digits (аналог «точность»).
     *       В спеке ошибочно названы setDigits/setFractionDigits.</li>
     *   <li>StringQualifiers: {@code setLength(int)} — совпадает со спеком.</li>
     * </ul>
     *
     * <p>Primitive types (BOOLEAN, DATE) и REF-типы требуют {@code IEObjectTypeContainer} для
     * наполнения {@code getTypes()} — отложено, реализуется в smoke step 6 при необходимости.
     */
    private static TypeDescription buildTypeDescription(List<ParsedType> types) {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        if (types == null) {
            return td;
        }
        for (ParsedType t : types) {
            switch (t.kind()) {
                case STRING:
                    if (t.length() != null) {
                        StringQualifiers sq = McoreFactory.eINSTANCE.createStringQualifiers();
                        sq.setLength(t.length());
                        td.setStringQualifiers(sq);
                    }
                    break;
                case NUMBER:
                    if (t.length() != null) {
                        NumberQualifiers nq = McoreFactory.eINSTANCE.createNumberQualifiers();
                        // setPrecision = total digits count; setScale = fraction digits
                        nq.setPrecision(t.length());
                        if (t.precision() != null) {
                            nq.setScale(t.precision());
                        }
                        td.setNumberQualifiers(nq);
                    }
                    break;
                case DATE:
                case BOOLEAN:
                case REF:
                    // Primitive types and references need IEObjectTypeContainer wiring.
                    // Deferred to smoke validation (Plan 2 Task 17 step 6).
                    break;
                default:
                    break;
            }
        }
        return td;
    }
}
