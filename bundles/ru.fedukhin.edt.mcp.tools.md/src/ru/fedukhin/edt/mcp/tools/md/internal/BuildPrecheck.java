package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.ArrayList;
import java.util.List;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckMarker;

/**
 * Триаж EDT-маркеров проекта перед сборкой {@code .epf}: что запрещает сборку,
 * а что лишь сопровождает её предупреждением.
 *
 * <p>Барьер нужен потому, что оба шага сборки — экспорт EDT→XML и
 * {@code 1cv8 DESIGNER /LoadExternalDataProcessorOrReportFromFiles} — синтаксис BSL
 * <b>не проверяют</b>: сломанный модуль молча уезжает в готовый файл и падает уже
 * у пользователя.
 *
 * <p>Блокирует только {@code severity=error} от {@code BslEditor} — компиляцию BSL.
 * Остальные error-маркеры (SSL-стиль, deprecation, {@code MdValidationChecker},
 * Web-клиентские несоответствия типов) EDT кладёт в ту же серьёзность, но загрузку
 * модуля в 1cv8 они не ломают — это code quality, а не runtime (CLAUDE.md проекта,
 * раздел про {@code deploy_project}; подтверждено live-smoke Stage 7b 2026-05-17).
 * Такие маркеры возвращаются в {@code warnings}, чтобы вызывающий их видел.
 *
 * <p>NB: {@code CheckRunResult.SeveritySummary.of} захардкожена на {@code blocker=0,
 * critical=0} (v1-маппинг: error→major, warning→minor, info→trivial), поэтому
 * «отказ по summary.blocker/critical» из плана Task 5 сработать не мог бы в принципе —
 * триаж строится на самих маркерах.
 */
public final class BuildPrecheck {

    /**
     * {@code Marker.getSourceType()} у маркеров BSL-редактора (Xtext). В DTO
     * {@link CheckMarker} это поле лежит в {@code checkId} — см. {@code MarkerReader.toDto}.
     */
    public static final String BSL_EDITOR = "BslEditor";

    private BuildPrecheck() { }

    /**
     * @param blockers маркеры, запрещающие сборку (пусто — можно собирать)
     * @param warnings человекочитаемые строки по остальным error/warning-маркерам
     */
    public record Verdict(List<CheckMarker> blockers, List<String> warnings) { }

    public static Verdict of(List<CheckMarker> markers) {
        List<CheckMarker> blockers = new ArrayList<>();
        List<String>      warnings = new ArrayList<>();
        for (CheckMarker m : markers) {
            boolean isError = "error".equals(m.severity());
            if (isError && BSL_EDITOR.equals(m.checkId())) {
                blockers.add(m);
            } else if (isError || "warning".equals(m.severity())) {
                warnings.add(format(m));
            }
            // severity=info — trivial, не показываем вовсе
        }
        return new Verdict(List.copyOf(blockers), List.copyOf(warnings));
    }

    /** Строка для {@code warnings[]} результата инструмента. */
    public static String format(CheckMarker m) {
        StringBuilder sb = new StringBuilder(m.severity()).append(" [").append(m.checkId()).append(']');
        if (m.line() > 0) sb.append(" line ").append(m.line());
        return sb.append(": ").append(m.message()).toString();
    }
}
