package ru.fedukhin.edt.mcp.tools.quality.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serialises tools.quality DTOs into {@code Map<String,Object>} for the MCP JSON encoder. */
public final class QualityJson {

    private QualityJson() {}

    /** {@code CheckEntry} → map. {@code includeDescription} = false drops {@code description}. */
    public static Map<String, Object> checkEntryToMap(CheckEntry e, boolean includeDescription) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkId",  e.checkId());
        m.put("title",    e.title());
        if (includeDescription) m.put("description", e.description());
        m.put("severity", e.severity());
        m.put("source",   e.source());
        if (e.category() != null) m.put("category", e.category());
        m.put("enabled",  e.enabled());
        return m;
    }

    /** {@code CheckMarker} → map. */
    public static Map<String, Object> checkMarkerToMap(CheckMarker m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("markerId",  m.markerId());
        out.put("project",   m.project());
        out.put("path",      m.path());
        out.put("line",      m.line());
        out.put("severity",  m.severity());
        out.put("checkId",   m.checkId());
        out.put("source",    m.source());
        out.put("message",   m.message());
        if (m.issueType() != null) out.put("issueType", m.issueType());
        return out;
    }

    /** {@code StandardRef} → map. */
    public static Map<String, Object> standardRefToMap(StandardRef ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("document", ref.document());
        m.put("section",  ref.section());
        if (ref.anchor() != null) m.put("anchor", ref.anchor());
        return m;
    }

    /** {@code SeveritySummary} → map. */
    public static Map<String, Object> severitySummaryToMap(CheckRunResult.SeveritySummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blocker",  s.blocker());
        m.put("critical", s.critical());
        m.put("major",    s.major());
        m.put("minor",    s.minor());
        m.put("trivial",  s.trivial());
        return m;
    }

    /** List of {@code CheckMarker} → list of maps. */
    public static List<Map<String, Object>> markersToList(List<CheckMarker> markers) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CheckMarker m : markers) out.add(checkMarkerToMap(m));
        return out;
    }
}
