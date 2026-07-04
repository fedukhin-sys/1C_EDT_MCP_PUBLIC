package ru.fedukhin.edt.mcp.core.privacy;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Детерминированная псевдонимизация значений через HMAC-SHA256. Обратной таблицы нет. */
public final class Pseudonymizer {

    private final byte[] key;

    public Pseudonymizer(byte[] key) {
        this.key = key.clone();
    }

    public String token(Sensitivity s, String value) {
        if (s == null || !s.isSensitive()) return value;
        if (s.fullHide()) return "[спец. категория ПДн скрыта]";
        return s.label() + "#" + shortHmac(normalize(value));
    }

    /** Нормализация: trim, схлопнуть пробелы, нижний регистр, ё→е. */
    static String normalize(String v) {
        if (v == null) return "";
        return v.trim().replaceAll("\\s+", " ").toLowerCase().replace("ё", "е");
    }

    private String shortHmac(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            // первые 4 байта → 8 hex-символов (32 бита — снижает риск коллизий при сохранении читабельности)
            return String.format("%02x%02x%02x%02x", h[0], h[1], h[2], h[3]);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }
}
