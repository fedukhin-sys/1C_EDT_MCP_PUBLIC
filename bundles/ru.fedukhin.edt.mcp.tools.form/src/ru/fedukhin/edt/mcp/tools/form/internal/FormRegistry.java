package ru.fedukhin.edt.mcp.tools.form.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Карта parent-kind → accessor name на ParentMdObject для коллекции форм.
 *
 * <p>У всех kind'ов с формами accessor один и тот же — {@code getForms()}; карта нужна ради
 * ответа на вопрос «а бывают ли у этого kind'а формы вообще».
 *
 * <p>Раньше список был обрезан до 6 kind'ов, из-за чего {@code list_forms} без parentFqn не
 * показывал формы бизнес-процессов, задач, планов счетов/видов расчёта/характеристик, планов
 * обмена, перечислений и журналов документов, а с parentFqn отбивал их как «не поддерживает
 * формы». Имя контейнера в Configuration берётся из
 * {@code MdObjectRegistry.containerFeatureName()} — второй карты заводить не нужно.
 *
 * <p>Конфигурация-level common forms — отдельный случай, обрабатывается в ListFormsTool
 * через {@code getCommonForms()} напрямую.
 */
public final class FormRegistry {

    private final Map<String, String> accessorByKind;

    public FormRegistry() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String kind : new String[]{
                "Catalog", "Document", "InformationRegister", "AccumulationRegister",
                "DataProcessor", "Report", "BusinessProcess", "Task",
                "ChartOfAccounts", "ChartOfCalculationTypes", "ChartOfCharacteristicTypes",
                "ExchangePlan", "Enum", "DocumentJournal"}) {
            m.put(kind, "getForms");
        }
        this.accessorByKind = Collections.unmodifiableMap(m);
    }

    /** Accessor name for getForms() on the parent MdObject, or null if not supported. */
    public String accessorFor(String parentKind) {
        return accessorByKind.get(parentKind);
    }

    /** The set of parent kinds that have a forms collection. */
    public Collection<String> supportedKinds() {
        return accessorByKind.keySet();
    }
}
