package ru.fedukhin.edt.mcp.tools.quality.internal;

import java.util.List;
import java.util.Set;

/**
 * Result of one {@code check_run} call — markers, completion flag, severity summary,
 * any checkIds the catalog didn't recognise.
 */
public record CheckRunResult(
        boolean completed,
        int waitedSeconds,
        List<CheckMarker> markers,
        SeveritySummary summary,
        Set<String> unknownCheckIds) {

    /** Per-severity counts. */
    public record SeveritySummary(int blocker, int critical, int major, int minor, int trivial) {

        /**
         * Builds a summary from the markers' MCP-mapped severities.
         * <p>v1 mapping (per Plan 2 §Task 7): {@code "error" → major}, {@code "warning" → minor},
         * {@code "info" → trivial}. The 5-level (blocker/critical/major/minor/trivial) shape is
         * preserved for forward-compatibility but the finer split is Stage 4+.
         */
        public static SeveritySummary of(List<CheckMarker> markers) {
            int err = 0, warn = 0, info = 0;
            for (CheckMarker m : markers) {
                switch (m.severity()) {
                    case "error"   -> err++;
                    case "warning" -> warn++;
                    case "info"    -> info++;
                    default -> {}
                }
            }
            return new SeveritySummary(0, 0, err, warn, info);
        }
    }
}
