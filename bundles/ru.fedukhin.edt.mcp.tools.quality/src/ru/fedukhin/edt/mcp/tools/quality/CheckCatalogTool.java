package ru.fedukhin.edt.mcp.tools.quality;

import jakarta.inject.Inject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckCatalog;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckEntry;
import ru.fedukhin.edt.mcp.tools.quality.internal.QualityArgs;
import ru.fedukhin.edt.mcp.tools.quality.internal.QualityJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code check_catalog} — list checks installed in EDT, filtered by substring/severity/source. */
public class CheckCatalogTool implements IMcpTool {

    private final CheckCatalog catalog;

    @Inject public CheckCatalogTool(CheckCatalog catalog) { this.catalog = catalog; }

    @Override public String name() { return "check_catalog"; }

    @Override public String description() {
        return "List checks installed in EDT; optional filters: filter (title/description substring), "
             + "severity (error/warning/info), source (v8codestyle/dt.check/edt/other)";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filter",   new LinkedHashMap<>(str));
        properties.put("severity", new LinkedHashMap<>(str));
        properties.put("source",   new LinkedHashMap<>(str));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        String filter   = QualityArgs.optStringArg(args, "filter");
        String severity = QualityArgs.optStringArg(args, "severity");
        String source   = QualityArgs.optStringArg(args, "source");
        List<CheckEntry> hits = catalog.list(filter, severity, source);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CheckEntry e : hits) out.add(QualityJson.checkEntryToMap(e, false));
        return out;
    }
}
