package ru.fedukhin.edt.mcp.tools.privacy.internal;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import java.util.function.Function;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.privacy.AttributeNameDictionary;
import ru.fedukhin.edt.mcp.core.privacy.BslTypeNames;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalog;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

/** Строит каталог ПДн по модели метаданных проекта (эвристика имён, fail-closed). */
public final class MetadataScanner {

    private final IBmModelManager bm;
    private final IConfigurationProvider cfg;

    public MetadataScanner(IBmModelManager bm, IConfigurationProvider cfg) {
        this.bm = bm;
        this.cfg = cfg;
    }

    /** Эвристика по имени объекта. */
    public static Sensitivity classifyObject(String kind, String name) {
        String n = name.toLowerCase().replace("ё", "е");
        if (n.contains("физическ") || n.contains("физлиц") || n.contains("сотрудник")
                || n.contains("пользовател") || n.contains("работник") || n.contains("контактноелицо"))
            return Sensitivity.PERSONAL;
        if (n.contains("контрагент") || n.contains("поставщик") || n.contains("покупател")
                || n.contains("клиент"))
            return Sensitivity.COUNTERPARTY;
        if (n.contains("организаци"))
            return Sensitivity.ORGANIZATION;
        return Sensitivity.NONE;
    }

    /** Эвристика по имени реквизита; typeResolver отдаёт класс объекта, на который ссылается тип. */
    public static Sensitivity classifyAttribute(String objectFullName, String attrName, String attrType,
                                                 Function<String, Sensitivity> typeResolver) {
        Sensitivity byName = AttributeNameDictionary.classify(attrName);
        if (byName.isSensitive()) return byName;
        var refObj = BslTypeNames.objectFullName(attrType);
        if (refObj.isPresent()) {
            Sensitivity byType = typeResolver.apply(refObj.get());
            if (byType.isSensitive()) return byType;
        }
        return Sensitivity.NONE;
    }

    /** Перегрузка для случаев без резолвера типов. */
    public static Sensitivity classifyAttribute(String objectFullName, String attrName, String attrType) {
        return classifyAttribute(objectFullName, attrName, attrType, x -> Sensitivity.NONE);
    }

    /**
     * Полный обход конфигурации проекта. Классифицирует объекты по имени
     * (classifyObject) и реквизиты по имени (AttributeNameDictionary.classify) —
     * без чтения типов реквизитов через BM-рефлексию (см. брифинг задачи 12).
     */
    public PiiCatalog scan(IProject project) {
        PiiCatalog.Builder b = PiiCatalog.builder();
        bm.executeReadOnlyTask(project, (IBmSingleNamespaceTask<Void>) txn -> {
            Configuration c = resolveConfiguration(txn, project);
            if (c == null) return null;
            scanCollection(b, c, "getCatalogs", "Справочник");
            scanCollection(b, c, "getDocuments", "Документ");
            return null;
        });
        return b.build();
    }

    private void scanCollection(PiiCatalog.Builder b, Configuration c, String getter, String prefix) {
        try {
            Object listObj = c.getClass().getMethod(getter).invoke(c);
            if (!(listObj instanceof Iterable<?> it)) return;
            for (Object o : it) {
                if (!(o instanceof EObject eo)) continue;
                String name = String.valueOf(eo.getClass().getMethod("getName").invoke(eo));
                String full = prefix + "." + name;
                String kind = prefix.equals("Справочник") ? "Catalog" : "Document";
                Sensitivity objS = classifyObject(kind, name);
                if (objS.isSensitive()) b.object(full, objS);
                scanAttributes(b, eo, full);
            }
        } catch (ReflectiveOperationException ignored) {
            // коллекция/метод отсутствует в этой версии mdclass API — пропускаем
        }
    }

    private void scanAttributes(PiiCatalog.Builder b, EObject eo, String full) {
        try {
            Object attrs = eo.getClass().getMethod("getAttributes").invoke(eo);
            if (!(attrs instanceof Iterable<?> ait)) return;
            for (Object a : ait) {
                if (!(a instanceof EObject ae)) continue;
                String an = String.valueOf(ae.getClass().getMethod("getName").invoke(ae));
                Sensitivity as = AttributeNameDictionary.classify(an);
                if (as.isSensitive()) b.attribute(full, an, as);
            }
        } catch (ReflectiveOperationException ignored) {
            // объект без getAttributes() (напр. Перечисление) — пропускаем
        }
    }

    private Configuration resolveConfiguration(IBmTransaction txn, IProject project) {
        IBmObject byLiteral = txn.getTopObjectByFqn("Configuration");
        if (byLiteral instanceof Configuration cc) return cc;
        Configuration global = cfg.getConfiguration(project);
        if (global != null) {
            EObject viaProvider = txn.toTransactionObject(global);
            if (viaProvider instanceof Configuration cc) return cc;
        }
        return null;
    }
}
