package ru.fedukhin.edt.mcp.tools.infobase.internal;

import java.util.List;

/**
 * Контекст «после загрузки» шага {@code update-project} (fix round 9, m2): сценарии 2 и 3 уже загрузили в базу
 * {@code .dt} или {@code .cfe}, и всё, чем кончится обновление проекта, — отказ O1, любой другой сбой, предупреждение
 * итога — несёт одно указание: загрузка сделана, проект не обновлён (или обновлён не полностью), {@code deploy_project}
 * сейчас не вызывать (он заменил бы только что загруженное содержимым проекта), правки сохранить отдельно и забрать
 * базу в проект с {@code discardProjectChanges: true}. Заменяет прежнюю подмену текста одного отказа O1 (fix round 6b);
 * передаётся в {@link InfobaseJobs#updateProject} (вариант с {@code AfterLoad}).
 *
 * <p>Только свои типы и JDK: в Guice не биндится, живёт в теле задания.
 *
 * @param loadDone что уже загружено — с маленькой буквы, без точки: «файл .dt «x.dt» уже загружен в базу Y»
 * @param notUpdatedProjects проекты, которые задание уже не обновит, если это обновление упадёт (у проекта
 *     конфигурации в {@code restore_infobase_from_dt} — проекты его расширений); называются в тексте отказа и сбоя
 */
public record AfterLoad(String loadDone, List<String> notUpdatedProjects) {

    /** Что делать после загрузки, если проект из базы не обновлён: общее у отказа, сбоя и предупреждения. */
    private static final String GUIDANCE = "Не вызывайте сейчас deploy_project: он заменит только что загруженную "
        + "конфигурацию базы содержимым проекта. Нужные правки сохраните отдельно (например, в git), затем заберите "
        + "конфигурацию базы в проект: update_project_from_infobase с discardProjectChanges: true.";

    public AfterLoad {
        notUpdatedProjects = List.copyOf(notUpdatedProjects);
    }

    /** Отказ O1 (EDT не подтвердила, что в проекте нет изменений, которых нет в базе) — после загрузки. */
    String refusal(List<String> atRisk) {
        return SyncV2.atRiskAfterLoadMessage(loadDone, atRisk) + notUpdated();
    }

    /**
     * Любой другой сбой обновления проекта после загрузки: исходная причина — в тексте, а за ней — указание «после
     * загрузки» (сбой мог прийти и после частичного слияния, поэтому «не обновлён или обновлён не полностью»).
     */
    String failure(String project, String reason) {
        return loadDone + ", но проект " + project + " из базы не обновлён или обновлён не полностью: "
            + withoutFinalDot(reason) + ". " + GUIDANCE + notUpdated();
    }

    /**
     * Предупреждение итога (правки проекта остались, аномалия «изменений нет» и т.п.) — с указанием «после
     * загрузки» в конце; итог без предупреждения не меняется. Проекты, которые задание не обновило, здесь не
     * называются: обновление прошло, и задание идёт дальше.
     */
    ProjectFromInfobaseUpdater.Outcome decorate(ProjectFromInfobaseUpdater.Outcome outcome) {
        if (outcome == null || outcome.warning() == null) return outcome;
        return new ProjectFromInfobaseUpdater.Outcome(outcome.resolution(), outcome.durationMs(),
            withoutFinalDot(outcome.warning()) + "; " + loadDone + " — не вызывайте сейчас deploy_project: он заменит "
                + "только что загруженную конфигурацию базы содержимым проекта; нужные правки сохраните отдельно "
                + "(например, в git), затем заберите конфигурацию базы в проект: update_project_from_infobase с "
                + "discardProjectChanges: true");
    }

    private String notUpdated() {
        return notUpdatedProjects.isEmpty() ? ""
            : " Проекты расширений этой конфигурации (" + String.join(", ", notUpdatedProjects)
                + ") задание тоже не обновляло.";
    }

    private static String withoutFinalDot(String text) {
        String t = text == null ? "" : text.strip();
        return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }
}
