package ru.fedukhin.edt.mcp.tools.privacy;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.privacy.AuditLog;

/**
 * {@code get_privacy_audit} — журнал обезличивания (мета, без самих ПДн).
 */
public final class GetPrivacyAuditTool implements IMcpTool {

    private final AuditLog audit;

    @Inject
    public GetPrivacyAuditTool(AuditLog audit) {
        this.audit = audit;
    }

    @Override public String name() { return "get_privacy_audit"; }

    @Override public String description() { return "Журнал обезличивания (мета, без самих ПДн)"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("limit", Map.of("type", "integer"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) {
        int limit = (args.get("limit") instanceof Number n) ? Math.max(1, n.intValue()) : 100;
        return Map.of("entries", audit.recent(limit));
    }
}
