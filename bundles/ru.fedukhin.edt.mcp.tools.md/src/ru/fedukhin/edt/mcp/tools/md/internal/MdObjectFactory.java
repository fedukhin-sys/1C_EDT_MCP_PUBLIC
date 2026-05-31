package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EStructuralFeature;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Создаёт top-object MdObject выбранного kind в проекте.
 *
 * <p>Применённые Spike-amendments (см. {@code docs/superpowers/notes/2026-05-15-spike-stage-5.md}):
 * <ul>
 *   <li>Spike 6: {@code getTopObjectByFqn(String)} / {@code attachTopObject(IBmObject, String)} —
 *       без namespace параметра. Транзакция уже namespace-scoped.</li>
 *   <li>Spike 8: Configuration root через литерал {@code "Configuration"}; fallback через
 *       {@link IConfigurationProvider}+{@code toTransactionObject} если null.</li>
 *   <li>Spike 3: FQN литералом {@code "<kind>.<name>"} — {@code ITopObjectFqnGenerator} не используется.</li>
 *   <li>Spike 2 Order A: containment первый, потом {@code attachTopObject}.</li>
 * </ul>
 *
 * <p>Делает всю работу в одной BM read-write транзакции:
 *  1. находит Configuration root;
 *  2. проверяет, что top-object с таким FQN не существует (иначе ToolException);
 *  3. создаёт EObject через {@code MdClassFactory.eINSTANCE.create<Kind>()};
 *  4. устанавливает name через reflection (универсально для всех kind'ов);
 *  5. добавляет в EList Configuration по containerFeature из {@link MdObjectRegistry};
 *  6. вызывает {@code txn.attachTopObject(obj, fqn)}.
 */
public final class MdObjectFactory {

    private final BmPersistentExecutor   executor;
    private final IConfigurationProvider cfgProvider;
    private final MdObjectRegistry       registry;

    @Inject
    public MdObjectFactory(BmPersistentExecutor executor,
                           IConfigurationProvider cfgProvider,
                           MdObjectRegistry registry) {
        this.executor = executor;
        this.cfgProvider = cfgProvider;
        this.registry = registry;
    }

    /**
     * Создаёт MdObject и возвращает его FQN.
     *
     * @throws ToolException — unknown kind / non-creatable kind / duplicate name
     */
    public String create(IProject project, String kindName, String name,
                         String synonym, String comment) throws ToolException {
        MdObjectKind kind = registry.get(kindName);
        if (kind == null) {
            throw new ToolException("md object kind '" + kindName + "' is not supported");
        }
        if (kind.containerFeatureName() == null) {
            throw new ToolException("md object kind '" + kindName + "' is not creatable (root or virtual)");
        }
        String fqn = kindName + "." + name;
        Throwable[] err = new Throwable[1];
        executor.execute(project, "MCP create " + fqn, (IBmSingleNamespaceTask<Void>) txn -> {
            try {
                if (txn.getTopObjectByFqn(fqn) != null) {
                    throw new ToolException("name '" + name + "' already exists in project '"
                            + project.getName() + "' for kind " + kindName);
                }
                Configuration cfg = resolveConfiguration(txn, project);
                EObject obj = createInstance(kindName);
                invokeSetName(obj, name);
                // setUuid defensive — attachTopObject обычно присваивает uuid для
                // top-objects сам, но child-MdObject (AttributeFactory) точно не
                // получает auto-uuid → deploy_project падает «uuid is null».
                // Делаем явно везде где создаём MdObject.
                invokeSetUuid(obj, java.util.UUID.randomUUID());
                if (comment != null) invokeSetComment(obj, comment);
                // BUG-15: write the <synonym> localised string when provided.
                if (synonym != null && !synonym.isEmpty()) {
                    applySynonym(obj, cfg, synonym);
                }
                applyKindDefaults(obj, kindName);
                addToContainer(cfg, kind.containerFeatureName(), obj);
                txn.attachTopObject((IBmObject) obj, fqn);
                return null;
            } catch (ToolException e) {
                err[0] = e;
                return null;
            }
        });
        if (err[0] instanceof ToolException) throw (ToolException) err[0];
        return fqn;
    }

    /**
     * Spike 8: Path 1 (literal "Configuration") + Path 2 (IConfigurationProvider fallback).
     * <p>
     * {@code txn.getTopObjectByFqn} returns {@code IBmObject}; Configuration is an EObject
     * that also implements IBmObject at runtime in the BM model.
     * For the fallback path, {@code cfgProvider.getConfiguration(project)} returns a
     * Configuration (EObject), which we promote to its in-transaction copy via
     * {@code txn.toTransactionObject} (generic return type T extends EObject, so the
     * assignment target type governs the cast implicitly — we just check instanceof).
     */
    private Configuration resolveConfiguration(com._1c.g5.v8.bm.core.IBmTransaction txn, IProject project)
            throws ToolException {
        IBmObject byLiteral = txn.getTopObjectByFqn("Configuration");
        if (byLiteral instanceof Configuration) {
            return (Configuration) byLiteral;
        }
        Configuration global = cfgProvider.getConfiguration(project);
        if (global != null) {
            EObject viaProvider = txn.toTransactionObject(global);
            if (viaProvider instanceof Configuration) {
                return (Configuration) viaProvider;
            }
        }
        throw new ToolException("cannot resolve Configuration root in project '"
                + project.getName() + "'");
    }

    private static EObject createInstance(String kindName) throws ToolException {
        // Reflective dispatch — avoids a 10-case switch over MdClassFactory.create* methods.
        // Method name pattern: create<Kind>(): <Kind>.
        try {
            return (EObject) MdClassFactory.eINSTANCE.getClass()
                    .getMethod("create" + kindName)
                    .invoke(MdClassFactory.eINSTANCE);
        } catch (ReflectiveOperationException e) {
            throw new ToolException("internal: cannot create instance for kind " + kindName);
        }
    }

    private static void invokeSetName(EObject obj, String name) {
        try {
            obj.getClass().getMethod("setName", String.class).invoke(obj, name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("setName missing on " + obj.getClass(), e);
        }
    }

    private static void invokeSetComment(EObject obj, String comment) {
        try {
            obj.getClass().getMethod("setComment", String.class).invoke(obj, comment);
        } catch (ReflectiveOperationException e) {
            // some kinds may not have comment — silently skip
        }
    }

    /**
     * BUG-15: writes the {@code <synonym>} localised string of a freshly created
     * MdObject. The synonym is an EMF {@code EMap<String,String>} keyed by language
     * code; the configuration's default language code is used (fallback {@code "ru"}).
     * Silently skipped for kinds without a synonym feature (e.g. CommonModule).
     */
    private static void applySynonym(EObject obj, Configuration cfg, String synonym) {
        String langCode = "ru";
        try {
            Language lang = cfg.getDefaultLanguage();
            if (lang != null && lang.getLanguageCode() != null && !lang.getLanguageCode().isEmpty()) {
                langCode = lang.getLanguageCode();
            }
        } catch (RuntimeException ignored) {
            // default language unavailable — fall back to "ru"
        }
        try {
            Object synonymEMap = obj.getClass().getMethod("getSynonym").invoke(obj);
            if (synonymEMap != null) {
                // getSynonym() returns an EMF EMap<String,String>, which does NOT
                // implement java.util.Map — call its own put(Object,Object).
                synonymEMap.getClass()
                        .getMethod("put", Object.class, Object.class)
                        .invoke(synonymEMap, langCode, synonym);
            }
        } catch (ReflectiveOperationException e) {
            // kind has no synonym feature (e.g. CommonModule) — skip
        }
    }

    private static void invokeSetUuid(EObject obj, java.util.UUID uuid) {
        try {
            obj.getClass().getMethod("setUuid", java.util.UUID.class).invoke(obj, uuid);
        } catch (ReflectiveOperationException e) {
            // unlikely — all MdObject subtypes have setUuid; silently skip on weird kinds
        }
    }

    /**
     * Заполняет «sane defaults» в свежесозданный MdObject через EMF feature API.
     * Без этого EDT генерит «голый» .mdo (только &lt;producedTypes&gt; + &lt;name&gt;),
     * deploy выдаёт «Некорректный состав регистраторов» и «Управляемая блокировка
     * не установлена». См. [[bug-register-mdo-skeleton]] §1.
     *
     * <p>В этом проходе ставим только enum-флаги, которые ВСЕГДА имеют 1 правильное
     * умолчание: {@code dataLockControlMode=Managed} для всех (1С 8.3+ стандарт);
     * {@code registerType=Turnovers} для AccReg; {@code writeMode=Independent}
     * для InfReg. {@code recorders}, {@code &lt;types&gt;} для REF Dimension,
     * {@code modulePath} для Document — отдельные follow-up'ы.
     */
    private static void applyKindDefaults(EObject obj, String kindName) {
        // Managed lock — единое умолчание для всех data-kinds (Catalog/Document/Register/etc).
        // Если у EClass нет такого feature — skip (Constant/Subsystem/etc).
        setEnumDefault(obj, "dataLockControlMode", "Managed");

        switch (kindName) {
            case "AccumulationRegister" -> setEnumDefault(obj, "registerType", "Turnovers");
            case "InformationRegister" -> setEnumDefault(obj, "writeMode", "Independent");
            default -> { /* no kind-specific extras yet */ }
        }
    }

    private static void setEnumDefault(EObject obj, String featureName, String literal) {
        EStructuralFeature feature = obj.eClass().getEStructuralFeature(featureName);
        if (feature == null || !(feature.getEType() instanceof EEnum eEnum)) {
            return;   // нет такого свойства у kind'а — это норма
        }
        EEnumLiteral lit = eEnum.getEEnumLiteralByLiteral(literal);
        if (lit == null) {
            return;   // enum literal не найден — не падаем, просто skip
        }
        obj.eSet(feature, lit.getInstance());
    }

    @SuppressWarnings("unchecked")
    private static void addToContainer(Configuration cfg, String accessor, EObject obj) {
        try {
            Object list = cfg.getClass().getMethod(accessor).invoke(cfg);
            ((List<EObject>) list).add(obj);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("missing accessor " + accessor + " on Configuration", e);
        }
    }
}
