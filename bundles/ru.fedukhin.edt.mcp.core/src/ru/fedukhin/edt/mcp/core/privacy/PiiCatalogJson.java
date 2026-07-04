package ru.fedukhin.edt.mcp.core.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;

/** Сериализация каталога ПДн в человекочитаемый JSON (в git как «Перечень обрабатываемых ПДн»). */
public final class PiiCatalogJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PiiCatalogJson() {}

    public static String write(PiiCatalog c, String generatedAtIso) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("generatedAt", generatedAtIso);
        ObjectNode objs = root.putObject("objects");
        c.objects().forEach((k, v) -> objs.put(k, v.name()));
        ObjectNode attrs = root.putObject("attributes");
        c.attributes().forEach((k, v) -> attrs.put(k, v.name()));
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize PII catalog", e);
        }
    }

    public static PiiCatalog read(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            PiiCatalog.Builder b = PiiCatalog.builder();
            readSection(root.get("objects"), (k, v) -> b.object(k, parse(v)));
            readSection(root.get("attributes"), (k, v) -> {
                int bar = k.indexOf('|');
                if (bar > 0) b.attribute(k.substring(0, bar), k.substring(bar + 1), parse(v));
            });
            return b.build();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid PII catalog json: " + e.getMessage(), e);
        }
    }

    private interface Sink { void accept(String key, String value); }

    private static void readSection(JsonNode node, Sink sink) {
        if (node == null || !node.isObject()) return;
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            sink.accept(e.getKey(), e.getValue().asText());
        }
    }

    private static Sensitivity parse(String v) {
        try { return Sensitivity.valueOf(v); }
        catch (IllegalArgumentException e) { return Sensitivity.PERSONAL; } // fail-closed
    }
}
