package ru.fedukhin.edt.mcp.tools.form.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Карта parent-kind → accessor name на ParentMdObject для коллекции форм.
 *
 * Spike §9.4 amendment: все 6 форм-поддерживающих kinds имеют `getForms()`.
 * Конфигурация-level common forms — отдельный случай, обрабатывается в ListFormsTool
 * через `getCommonForms()` напрямую.
 */
public final class FormRegistry {

    private final Map<String, String> accessorByKind;

    public FormRegistry() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Catalog",              "getForms");
        m.put("Document",             "getForms");
        m.put("InformationRegister",  "getForms");
        m.put("AccumulationRegister", "getForms");
        m.put("DataProcessor",        "getForms");
        m.put("Report",               "getForms");
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
