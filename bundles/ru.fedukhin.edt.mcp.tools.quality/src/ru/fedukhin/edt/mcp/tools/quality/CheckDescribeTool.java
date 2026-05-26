package ru.fedukhin.edt.mcp.tools.quality;

import jakarta.inject.Inject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckCatalog;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckEntry;
import ru.fedukhin.edt.mcp.tools.quality.internal.QualityArgs;
import ru.fedukhin.edt.mcp.tools.quality.internal.QualityJson;
import ru.fedukhin.edt.mcp.tools.quality.internal.StandardRef;
import ru.fedukhin.edt.mcp.tools.quality.internal.StandardReferenceResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code check_describe} — describe one check; surfaces standardRef when available. */
public class CheckDescribeTool implements IMcpTool {

    private final CheckCatalog               catalog;
    private final StandardReferenceResolver  standardRef;

    @Inject
    public CheckDescribeTool(CheckCatalog catalog, StandardReferenceResolver standardRef) {
        this.catalog     = catalog;
        this.standardRef = standardRef;
    }

    @Override public String name() { return "check_describe"; }

    @Override public String description() {
        return "Describe one check by id; includes description and optional standardRef "
             + "(Стандарты разработки V8 section/anchor) for v8codestyle checks";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("checkId", new LinkedHashMap<>(str));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("checkId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        String checkId = QualityArgs.stringArg(args, "checkId");
        CheckEntry entry = catalog.get(checkId).orElseThrow(() ->
                new ToolException("check '" + checkId + "' not found"));
        Map<String, Object> out = QualityJson.checkEntryToMap(entry, true);
        Optional<StandardRef> ref = standardRef.resolve(bundleHintFor(entry), checkId);
        ref.ifPresent(r -> out.put("standardRef", QualityJson.standardRefToMap(r)));
        return out;
    }

    /** Hint resolver maps {@code CheckEntry.source} back to a plausible bundle FQN prefix. */
    private static String bundleHintFor(CheckEntry e) {
        return switch (e.source()) {
            case "v8codestyle" -> "com.e1c.v8codestyle.bsl";
            case "dt.check"    -> "com.e1c.dt.check.md";
            case "edt"         -> "com.e1c.g5.v8.dt.bsl.check";
            default            -> "";
        };
    }
}
