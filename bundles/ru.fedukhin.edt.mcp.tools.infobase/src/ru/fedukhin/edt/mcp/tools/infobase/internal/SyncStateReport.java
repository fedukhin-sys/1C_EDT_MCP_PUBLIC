package ru.fedukhin.edt.mcp.tools.infobase.internal;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Части ответа {@code get_infobase_sync_state} — строки проектов и детали — JSON-подобными картами. */
public final class SyncStateReport {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private SyncStateReport() {}

    /** Строка проекта: состояния есть всегда (неизвестное — {@code null}), {@code error} — только непустая. */
    public static Map<String, Object> row(ProjectSyncState state) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("project", state.project());
        row.put("kind", state.kind());
        row.put("equalityState", state.equalityState());
        row.put("connected", state.connected());
        row.put("updateState", UpdateStateRule.projectState(state));
        row.put("projectDirty", state.projectDirty());
        if (state.error() != null) row.put("error", state.error());
        return row;
    }

    /**
     * Детали проекта: текущие подписи против снимка в памяти EDT ({@code memory} — с ним сравнивает
     * {@code getEqualityState} сейчас) и на диске ({@code disk} — его EDT загрузит после перезапуска; совпадает с
     * памятью — только {@code sameAsMemory: true}). Записи проекта в снимке нет — сравнение с пустым, как у EDT.
     *
     * @param current текущие подписи; {@code null} — не получены, причина в {@code currentFailure}
     */
    public static Map<String, Object> details(String project, Map<String, byte[]> current, String currentFailure,
                                              EdtSyncSnapshot.Read snapshot, int maxFiles) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        out.put("currentFiles", current == null ? null : current.size());
        if (current == null) notes.add("подписи ресурсов проекта не получены: " + currentFailure);

        Map<String, byte[]> memoryFiles = null;
        if (snapshot.memory() == null) {
            notes.add("снимок в памяти: " + snapshot.failure());
        } else {
            EdtSyncSnapshot.Recorded inMemory = snapshot.memory().get(project);
            memoryFiles = inMemory == null ? Map.of() : inMemory.files();
            if (inMemory == null) {
                notes.add("в снимке в памяти EDT нет записи этого проекта — для EDT все его файлы новые");
            }
            if (current != null) {
                out.put("memory", diff(SignatureDiff.compare(current, memoryFiles, maxFiles), inMemory));
            }
        }

        if (snapshot.disk() == null) {
            if (snapshot.diskFailure() != null) notes.add("снимок на диске: " + snapshot.diskFailure());
        } else {
            EdtSyncSnapshot.Recorded onDisk = snapshot.disk().get(project);
            Map<String, byte[]> diskFiles = onDisk == null ? Map.of() : onDisk.files();
            Boolean same = memoryFiles == null ? null : SignatureDiff.sameSignatures(memoryFiles, diskFiles);
            Map<String, Object> disk = new LinkedHashMap<>();
            disk.put("sameAsMemory", same);
            if (!Boolean.TRUE.equals(same) && current != null) {
                disk.putAll(diff(SignatureDiff.compare(current, diskFiles, maxFiles), onDisk));
            } else if (onDisk != null && onDisk.timestamp() > 0) {
                disk.put("time", time(onDisk.timestamp()));
            }
            if (onDisk == null && !Boolean.TRUE.equals(same)) notes.add("в снимке на диске нет записи этого проекта");
            out.put("disk", disk);
        }
        if (!notes.isEmpty()) out.put("note", String.join("; ", notes));
        return out;
    }

    /** Отметка времени снимка (мс) — местное время до секунд, без зоны. */
    public static String time(long millis) {
        return TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }

    private static Map<String, Object> diff(SignatureDiff.Result result, EdtSyncSnapshot.Recorded recorded) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", result.snapshotFiles());
        if (recorded != null && recorded.timestamp() > 0) out.put("time", time(recorded.timestamp()));
        out.put("matches", result.matches());
        out.put("addedCount", result.addedCount());
        out.put("added", result.added());
        out.put("deletedCount", result.deletedCount());
        out.put("deleted", result.deleted());
        out.put("changedCount", result.changedCount());
        List<Map<String, Object>> changed = new ArrayList<>();
        for (SignatureDiff.Changed change : result.changed()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", change.path());
            item.put("snapshot", change.snapshot());
            item.put("current", change.current());
            changed.add(item);
        }
        out.put("changed", changed);
        out.put("emptySignatures", result.emptySignatures());
        out.put("truncated", result.truncated());
        return out;
    }
}
