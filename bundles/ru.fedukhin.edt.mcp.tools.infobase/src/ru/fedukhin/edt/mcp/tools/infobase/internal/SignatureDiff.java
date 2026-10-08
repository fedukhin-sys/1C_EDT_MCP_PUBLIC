package ru.fedukhin.edt.mcp.tools.infobase.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Подписи ресурсов проекта (путь от корня проекта через {@code /} → SHA-256 содержимого) против записанного снимка
 * синхронизации — по правилу {@code InfobaseSynchronizationStateManagerDelegate.checkAndUpdateSynchronizationState}
 * (EDT 2026.1, по байткоду): размеры разные, текущая карта пуста или подпись не равна по {@code Arrays.equals} —
 * проект «грязный». Пустая подпись в снимке ({@code null} или нулевой длины) — след забора из базы без пересчёта
 * подписей (живой прогон 1.24.0, L8).
 */
public final class SignatureDiff {

    /** Сколько первых шестнадцатеричных знаков подписи показывать. */
    public static final int HEX_PREFIX = 12;

    private SignatureDiff() {}

    /** Файл с другой подписью: первые знаки подписи в снимке и текущей ({@code ""} — подписи нет). */
    public record Changed(String path, String snapshot, String current) {}

    /**
     * {@code matches} — по правилу EDT проект совпадает со снимком. Списки отсортированы и усечены до {@code max},
     * счётчики — полные, {@code truncated} — усечён хоть один; {@code emptySignatures} — записей снимка без подписи.
     */
    public record Result(int currentFiles, int snapshotFiles, boolean matches, List<String> added, int addedCount,
                         List<String> deleted, int deletedCount, List<Changed> changed, int changedCount,
                         int emptySignatures, boolean truncated) {}

    public static Result compare(Map<String, byte[]> current, Map<String, byte[]> snapshot, int max) {
        TreeSet<String> added = new TreeSet<>();
        TreeSet<String> changed = new TreeSet<>();
        for (Map.Entry<String, byte[]> entry : current.entrySet()) {
            if (!snapshot.containsKey(entry.getKey())) {
                added.add(entry.getKey());
            } else if (!Arrays.equals(entry.getValue(), snapshot.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        TreeSet<String> deleted = new TreeSet<>();
        int empty = 0;
        for (Map.Entry<String, byte[]> entry : snapshot.entrySet()) {
            if (!current.containsKey(entry.getKey())) deleted.add(entry.getKey());
            if (entry.getValue() == null || entry.getValue().length == 0) empty++;
        }
        List<Changed> changes = new ArrayList<>();
        for (String path : first(changed, max)) {
            changes.add(new Changed(path, hexPrefix(snapshot.get(path)), hexPrefix(current.get(path))));
        }
        boolean matches = !current.isEmpty() && added.isEmpty() && deleted.isEmpty() && changed.isEmpty();
        boolean truncated = added.size() > max || deleted.size() > max || changed.size() > max;
        return new Result(current.size(), snapshot.size(), matches, first(added, max), added.size(),
            first(deleted, max), deleted.size(), changes, changed.size(), empty, truncated);
    }

    /** Одинаковы ли два снимка: те же пути и равные по {@code Arrays.equals} подписи. */
    public static boolean sameSignatures(Map<String, byte[]> a, Map<String, byte[]> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, byte[]> entry : a.entrySet()) {
            if (!b.containsKey(entry.getKey()) || !Arrays.equals(entry.getValue(), b.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Первые {@link #HEX_PREFIX} шестнадцатеричных знаков подписи; {@code ""} — подписи нет. */
    public static String hexPrefix(byte[] signature) {
        if (signature == null || signature.length == 0) return "";
        String hex = HexFormat.of().formatHex(signature);
        return hex.length() <= HEX_PREFIX ? hex : hex.substring(0, HEX_PREFIX);
    }

    private static List<String> first(TreeSet<String> sorted, int max) {
        List<String> out = new ArrayList<>();
        for (String path : sorted) {
            if (out.size() >= max) break;
            out.add(path);
        }
        return out;
    }
}
