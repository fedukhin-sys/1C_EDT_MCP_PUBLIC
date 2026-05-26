package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import jakarta.inject.Inject;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Whitelist setter для MdObject и его attributes (см. spec §5.4).
 *
 * <p>Whitelist:
 * <ul>
 *   <li>Any MdObject: synonym (String), comment (String).</li>
 *   <li>CommonModule additionally: server, client, externalConnection, global, privileged,
 *       serverCallsAllowed / serverCall (boolean — aliases).</li>
 *   <li>InformationRegister additionally: writeMode (String: Independent / RecorderSubordinate).</li>
 *   <li>Attribute/Dimension/Resource: synonym (String), comment (String);
 *       type — только через add_attribute/rename_attribute.</li>
 * </ul>
 *
 * <p>Spike 4 amendment: synonym пишется в {@code EMap<String,String>} с ключом = languageCode
 * (получается из {@link IV8Project#getDefaultLanguage()} с fallback на "ru"). LocalString-типа
 * как такового в EDT не существует — {@code MdObject.getSynonym()} возвращает
 * {@code EMap<String, String>} напрямую.
 *
 * <p>Адаптации API (verified via javap на CommonModule.class):
 * <ul>
 *   <li>"client" → {@code setClientManagedApplication(boolean)}</li>
 *   <li>"serverCallsAllowed" → {@code setServerCall(boolean)} (не setServerCallsAllowed)</li>
 * </ul>
 */
public final class PropertyAccessor {

    private static final Set<String> COMMON_MD_PROPS = Set.of("synonym", "comment");
    private static final Set<String> COMMON_MODULE_FLAGS =
            Set.of("server", "client", "externalConnection", "global", "privileged",
                   "serverCallsAllowed", "serverCall");
    private static final Set<String> ATTRIBUTE_PROPS = Set.of("synonym", "comment", "type");
    /** InformationRegister-only properties (BUG-07). */
    private static final Set<String> INFORMATION_REGISTER_PROPS = Set.of("writeMode");

    private final IV8ProjectManager projectManager;

    @Inject
    public PropertyAccessor(IV8ProjectManager projectManager) {
        this.projectManager = projectManager;
    }

    /**
     * Устанавливает свойство {@code property} на объект {@code target}.
     *
     * @param target   MdObject или дочерний attribute/dimension/resource
     * @param kind     строковый kind ("Catalog", "CommonModule", "Attribute", "Dimension", "Resource", ...)
     * @param project  Eclipse-проект (нужен для определения языка при записи synonym)
     * @param property имя свойства из whitelist
     * @param value    новое значение (String или Boolean)
     * @throws ToolException если свойство не в whitelist, тип значения неверный,
     *                       или свойство "type" запрошено напрямую
     */
    public void set(EObject target, String kind, IProject project,
                    String property, Object value) throws ToolException {
        boolean isAttribute = "Attribute".equals(kind)
                || "Dimension".equals(kind)
                || "Resource".equals(kind);
        Set<String> allowed = isAttribute ? ATTRIBUTE_PROPS : COMMON_MD_PROPS;

        boolean isCommonModule = "CommonModule".equals(kind);
        boolean isInformationRegister = "InformationRegister".equals(kind);
        if (!allowed.contains(property)
                && !(isCommonModule && COMMON_MODULE_FLAGS.contains(property))
                && !(isInformationRegister && INFORMATION_REGISTER_PROPS.contains(property))) {
            throw new ToolException("property '" + property + "' is not whitelisted for kind " + kind);
        }
        try {
            switch (property) {
                case "comment":
                    invoke(target, "setComment", String.class, requireString(value, property));
                    break;
                case "synonym":
                    setSynonym(target, project, requireString(value, property));
                    break;
                case "type":
                    throw new ToolException(
                            "'type' is set via add_attribute/rename_attribute, not set_md_property");
                case "client":
                    // Spike adaptation: CommonModule.setClientManagedApplication(boolean)
                    invoke(target, "setClientManagedApplication", boolean.class,
                            requireBoolean(value, property));
                    break;
                case "serverCall":
                case "serverCallsAllowed":
                    // Both names map to CommonModule.setServerCall(boolean). "serverCall"
                    // is the 1C-side property name; "serverCallsAllowed" kept for compat.
                    invoke(target, "setServerCall", boolean.class, requireBoolean(value, property));
                    break;
                case "writeMode":
                    // BUG-07: InformationRegister write mode (Independent / RecorderSubordinate).
                    setWriteMode(target, requireString(value, property));
                    break;
                case "server":
                case "externalConnection":
                case "global":
                case "privileged":
                    String setter = "set" + Character.toUpperCase(property.charAt(0))
                            + property.substring(1);
                    invoke(target, setter, boolean.class, requireBoolean(value, property));
                    break;
                default:
                    throw new ToolException(
                            "property '" + property + "' is not whitelisted for kind " + kind);
            }
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("failed to set " + property + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void setSynonym(EObject target, IProject project, String value) throws ToolException {
        EMap<String, String> map;
        try {
            map = (EMap<String, String>) target.getClass().getMethod("getSynonym").invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new ToolException(
                    "target does not expose getSynonym(): " + target.getClass().getSimpleName());
        }
        if (map == null) {
            throw new ToolException(
                    "getSynonym() returned null on " + target.getClass().getSimpleName());
        }
        String langCode = "ru";
        if (project != null && projectManager != null) {
            try {
                IV8Project v8 = projectManager.getProject(project);
                if (v8 != null) {
                    Language lang = v8.getDefaultLanguage();
                    if (lang != null && lang.getLanguageCode() != null) {
                        langCode = lang.getLanguageCode();
                    }
                }
            } catch (Throwable t) {
                // keep "ru" fallback
            }
        }
        map.put(langCode, value);
    }

    /**
     * Sets {@code InformationRegister.writeMode} (BUG-07). The mdclass enum
     * {@code RegisterWriteMode} is resolved reflectively, so this class keeps no
     * compile-time dependency on the concrete enum type.
     */
    private static void setWriteMode(EObject target, String value) throws ToolException {
        try {
            Class<?> enumClass = Class.forName("com._1c.g5.v8.dt.metadata.mdclass.RegisterWriteMode");
            Object literal = enumClass.getMethod("get", String.class).invoke(null, value);
            if (literal == null) {
                literal = enumClass.getMethod("getByName", String.class).invoke(null, value);
            }
            if (literal == null) {
                throw new ToolException("unknown writeMode '" + value
                        + "'; expected 'Independent' or 'RecorderSubordinate'");
            }
            target.getClass().getMethod("setWriteMode", enumClass).invoke(target, literal);
        } catch (ToolException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw new ToolException("failed to set writeMode: " + e.getMessage());
        }
    }

    private static String requireString(Object v, String prop) throws ToolException {
        if (!(v instanceof String)) {
            throw new ToolException("property '" + prop + "' expects String, got "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return (String) v;
    }

    private static boolean requireBoolean(Object v, String prop) throws ToolException {
        if (!(v instanceof Boolean)) {
            throw new ToolException("property '" + prop + "' expects boolean, got "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return (Boolean) v;
    }

    private static void invoke(EObject obj, String method, Class<?> paramType, Object value) {
        try {
            obj.getClass().getMethod(method, paramType).invoke(obj, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    method + " missing on " + obj.getClass(), e);
        }
    }
}
