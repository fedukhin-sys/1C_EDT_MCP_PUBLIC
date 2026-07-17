package ru.fedukhin.edt.mcp.tools.eventlog;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogLocator;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogQuery;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventLogReader;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.EventRecord;
import ru.fedukhin.edt.mcp.tools.eventlog.internal.LgpParser;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

/**
 * Streams event-log records matching a filter spec. Accepts either an EDT-registered
 * infobase (resolves to {@code 1Cv8Log} via {@link EventLogLocator}) or an explicit
 * {@code logDir} so the tool also works for offline / off-host log copies.
 */
public class QueryEventLogTool implements IMcpTool {

    private static final int MAX_LIMIT = 10_000;

    private final InfobaseRegistry registry;

    @Inject
    public QueryEventLogTool(InfobaseRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "query_event_log"; }
    @Override public String description() {
        // Требование «один из name/uuid/logDir» объявлено в схеме через anyOf (публикуется
        // через JsonSchemaExtras); словами дублируем для клиентов, не читающих anyOf.
        return "Query the 1C event log (.lgf/.lgp) with filters: date range, user, event, severity, "
             + "comment, metadata. Requires exactly one of: name, uuid, or logDir.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name",        strProp("Infobase name registered in EDT"));
        properties.put("uuid",        strProp("Infobase UUID registered in EDT"));
        properties.put("logDir",      strProp("Direct path to the 1Cv8Log directory (skips infobase lookup)"));
        properties.put("srvinfoDir",  strProp("Override srvinfo path for SERVER infobases"));
        properties.put("clusterPort", intProp("Cluster port (default 1541)"));

        properties.put("from", strProp("Inclusive start date/time, ISO-8601 (e.g. 2026-04-01 or 2026-04-01T08:00:00)"));
        properties.put("to",   strProp("Inclusive end date/time, ISO-8601 (e.g. 2026-05-31T23:59:59)"));

        properties.put("severity",        arrOfStr("Severity filter: Information/Warning/Error/Note (or I/W/E/N)"));
        properties.put("user",            arrOfStr("User display name(s) (e.g. \"ИвановИИ\")"));
        properties.put("userUuid",        arrOfStr("User UUID(s) — alternative to user name"));
        properties.put("application",     arrOfStr("Application name(s) (e.g. 1CV8C, Designer, BackgroundJob)"));
        properties.put("event",           arrOfStr("Exact event name(s) (e.g. \"_$Data$_.Update\")"));
        properties.put("eventContains",   strProp("Substring filter on the event name (case-insensitive)"));
        properties.put("commentContains", strProp("Substring filter on the comment field (case-insensitive)"));
        properties.put("metadataContains",strProp("Substring filter on the metadata name (case-insensitive)"));

        Map<String, Object> sessionItem = new LinkedHashMap<>(); sessionItem.put("type", "integer");
        Map<String, Object> sessions = new LinkedHashMap<>();
        sessions.put("type", "array"); sessions.put("items", sessionItem);
        sessions.put("description", "Filter by session number(s)");
        properties.put("session", sessions);

        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer"); limit.put("minimum", 1); limit.put("maximum", MAX_LIMIT);
        limit.put("default", 100);
        properties.put("limit", limit);

        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("type", "integer"); offset.put("minimum", 0); offset.put("default", 0);
        properties.put("offset", offset);

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("type", "string"); order.put("enum", List.of("date_asc", "date_desc"));
        order.put("default", "date_asc");
        properties.put("order", order);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("anyOf", List.of(
            Map.of("required", List.of("name")),
            Map.of("required", List.of("uuid")),
            Map.of("required", List.of("logDir"))
        ));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        Path logDir = resolveLogDir(args);
        EventLogQuery q = buildQuery(args);
        EventLogReader reader = new EventLogReader();
        EventLogReader.Page page = reader.read(logDir, q);

        List<Map<String, Object>> records = new ArrayList<>(page.records.size());
        for (EventRecord ev : page.records) records.add(serialise(ev));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logDir", logDir.toString());
        out.put("matched", page.matchedTotal);
        out.put("scanned", page.scanned);
        out.put("returned", records.size());
        out.put("limit", q.limit);
        out.put("offset", q.offset);
        out.put("order", q.descending ? "date_desc" : "date_asc");
        if (page.truncated) out.put("truncated", true);
        // Активная партиция дописывается кластером: её хвост мог оборваться посреди записи.
        if (page.partial) out.put("partial", true);
        out.put("events", records);
        return out;
    }

    Path resolveLogDir(Map<String, Object> args) throws ToolException {
        String logDir = args == null ? null : (String) args.get("logDir");
        if (logDir != null && !logDir.isBlank()) {
            return Paths.get(logDir);
        }
        InfobaseReference ref = resolveInfobase(args);
        EventLogLocator locator = new EventLogLocator();
        Object srv = args == null ? null : args.get("srvinfoDir");
        if (srv instanceof String s && !s.isBlank()) locator.setSrvinfoDir(Paths.get(s));
        Object port = args == null ? null : args.get("clusterPort");
        if (port instanceof Number n) locator.setClusterPort(n.intValue());
        return locator.locate(ref).logDir;
    }

    InfobaseReference resolveInfobase(Map<String, Object> args) throws ToolException {
        String name = args == null ? null : (String) args.get("name");
        String uuidStr = args == null ? null : (String) args.get("uuid");
        if ((name == null || name.isEmpty()) && (uuidStr == null || uuidStr.isEmpty())) {
            throw new ToolException("either 'name', 'uuid', or 'logDir' is required");
        }
        Optional<InfobaseReference> hit;
        if (uuidStr != null && !uuidStr.isEmpty()) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidStr); }
            catch (IllegalArgumentException e) { throw new ToolException("invalid uuid: " + uuidStr); }
            hit = registry.findByUuid(uuid);
        } else {
            hit = registry.findByName(name);
        }
        return hit.orElseThrow(() -> new ToolException(
            "infobase '" + (uuidStr != null ? uuidStr : name) + "' not found"));
    }

    @SuppressWarnings("unchecked")
    EventLogQuery buildQuery(Map<String, Object> args) throws ToolException {
        EventLogQuery q = new EventLogQuery();
        if (args == null) return q;

        String from = (String) args.get("from");
        String to   = (String) args.get("to");
        try {
            if (from != null && !from.isBlank()) q.from(from);
            if (to   != null && !to.isBlank())   q.to(to);
        } catch (RuntimeException e) {
            throw new ToolException("invalid date: " + e.getMessage());
        }

        q.severity(asStringList(args.get("severity")));
        q.user(asStringList(args.get("user")));
        q.userUuid(asStringList(args.get("userUuid")));
        q.application(asStringList(args.get("application")));
        q.event(asStringList(args.get("event")));
        q.eventContains    = (String) args.get("eventContains");
        q.commentContains  = (String) args.get("commentContains");
        q.metadataContains = (String) args.get("metadataContains");

        Object session = args.get("session");
        if (session instanceof List<?> raw) {
            List<Long> longs = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof Number n) longs.add(n.longValue());
                else if (o instanceof String s && !s.isBlank()) {
                    try { longs.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) { /* skip */ }
                }
            }
            q.session(longs);
        } else if (session instanceof Number n) {
            q.session(List.of(n.longValue()));
        }

        Object limit = args.get("limit");
        if (limit instanceof Number n) q.limit = clamp(n.intValue(), 1, MAX_LIMIT);
        Object offset = args.get("offset");
        if (offset instanceof Number n) q.offset = Math.max(0, n.intValue());

        Object order = args.get("order");
        if ("date_desc".equals(order)) q.descending = true;
        return q;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s.isBlank() ? null : List.of(s);
        if (o instanceof List<?> raw) {
            List<String> out = new ArrayList<>();
            for (Object x : raw) if (x instanceof String s && !s.isBlank()) out.add(s);
            return out;
        }
        return null;
    }

    private static Map<String, Object> serialise(EventRecord ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", ev.dateIso);
        m.put("session", ev.session);
        m.put("user", ev.user);
        if (ev.userUuid != null) m.put("userUuid", ev.userUuid);
        m.put("computer", ev.computer);
        m.put("application", ev.application);
        m.put("connectionId", ev.connectionId);
        m.put("event", ev.event);
        m.put("severity", ev.severity);
        m.put("comment", ev.comment);
        if (ev.metadata != null) {
            m.put("metadata", ev.metadata);
            if (ev.metadataUuid != null) m.put("metadataUuid", ev.metadataUuid);
        }
        if (ev.dataPresentation != null && !ev.dataPresentation.isEmpty()) {
            m.put("dataPresentation", ev.dataPresentation);
        }
        if (ev.dataType != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", ev.dataType);
            data.put("value", ev.dataValue);
            m.put("data", data);
        }
        m.put("server", ev.server);
        m.put("mainPort", ev.mainPort);
        m.put("secondaryPort", ev.secondaryPort);
        m.put("txStatus", LgpParser.decodeTxStatus(ev.txStatus));
        if (ev.txId != null) m.put("txId", ev.txId);
        return m;
    }

    private static Map<String, Object> strProp(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> intProp(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "integer");
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> arrOfStr(String desc) {
        Map<String, Object> item = new LinkedHashMap<>(); item.put("type", "string");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array"); m.put("items", item); m.put("description", desc);
        return m;
    }

    @Override public boolean returnsInfobaseData() { return true; }

    /** name/uuid — объявленные аргументы этого инструмента, поэтому им можно верить. */
    @Override public String privacyInfobaseKey(Map<String, Object> args) {
        if (args == null) return null;
        if (args.get("name") instanceof String s && !s.isBlank()) return s;
        if (args.get("uuid") instanceof String s && !s.isBlank()) return s;
        return null;
    }
}
