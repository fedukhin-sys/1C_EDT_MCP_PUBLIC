package ru.fedukhin.edt.mcp.tools.privacy;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.privacy.CatalogStore;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalog;

/**
 * {@code get_pii_catalog} — текущий union-каталог ПДн открытых проектов.
 */
public final class GetPiiCatalogTool implements IMcpTool {

    private final CatalogStore store;

    @Inject
    public GetPiiCatalogTool(CatalogStore store) {
        this.store = store;
    }

    @Override public String name() { return "get_pii_catalog"; }

    @Override public String description() { return "Текущий union-каталог ПДн открытых проектов"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) {
        PiiCatalog c = store.current();
        Map<String, Object> objs = new LinkedHashMap<>();
        c.objects().forEach((k, v) -> objs.put(k, v.name()));
        Map<String, Object> attrs = new LinkedHashMap<>();
        c.attributes().forEach((k, v) -> attrs.put(k, v.name()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("objects", objs);
        out.put("attributes", attrs);
        return out;
    }
}
