package ru.fedukhin.edt.mcp.tools.privacy;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.privacy.AuditLog;
import ru.fedukhin.edt.mcp.core.privacy.InfobaseFlagStore;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

/**
 * {@code set_infobase_pii_flag} — установить флаг containsRealPersonalData у инфобазы
 * (снятие = объявить тестовую БД без ПДн, решение логируется в audit).
 */
public final class SetInfobasePiiFlagTool implements IMcpTool {

    private final InfobaseFlagStore flags;
    private final AuditLog audit;

    @Inject
    public SetInfobasePiiFlagTool(InfobaseFlagStore flags, AuditLog audit) {
        this.flags = flags;
        this.audit = audit;
    }

    @Override public String name() { return "set_infobase_pii_flag"; }

    @Override public String description() {
        return "Установить флаг containsRealPersonalData у инфобазы (снятие = объявить тестовую БД без ПДн, логируется)";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("infobase", Map.of("type", "string"));
        props.put("containsRealPersonalData", Map.of("type", "boolean"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("infobase", "containsRealPersonalData"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        if (!(args.get("infobase") instanceof String ib) || ib.isEmpty())
            throw new ToolException("'infobase' must be a non-empty string");
        if (!(args.get("containsRealPersonalData") instanceof Boolean flag))
            throw new ToolException("'containsRealPersonalData' must be boolean");
        flags.setFlag(ib, flag);
        // фиксируем решение в журнале (без ПДн)
        audit.record("set_infobase_pii_flag", ib, Sensitivity.NONE,
                     "containsRealPersonalData=" + flag, 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("infobase", ib);
        out.put("containsRealPersonalData", flag);
        return out;
    }
}
