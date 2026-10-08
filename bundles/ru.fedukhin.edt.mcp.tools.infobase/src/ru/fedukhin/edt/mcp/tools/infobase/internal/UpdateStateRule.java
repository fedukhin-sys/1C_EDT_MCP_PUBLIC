package ru.fedukhin.edt.mcp.tools.infobase.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Как 1C:EDT решает при запуске клиента, обновлять ли базу, и что видит пользователь — по байткоду EDT 2026.1:
 * {@code InfobaseApplicationProvisionDelegate.getUpdateState} (правило) и {@code ApplicationUiSupport.ensureUpdated}
 * (окно «Обновление приложения»). Состояния — строки, имена констант EDT ({@code InfobaseEqualityState},
 * {@code ApplicationUpdateState}): классы EDT правилу не нужны.
 */
public final class UpdateStateRule {

    public static final String UPDATED = "UPDATED";
    public static final String INCREMENTAL_UPDATE_REQUIRED = "INCREMENTAL_UPDATE_REQUIRED";
    public static final String FULL_UPDATE_REQUIRED = "FULL_UPDATE_REQUIRED";
    public static final String BEING_UPDATED = "BEING_UPDATED";
    public static final String UNKNOWN = "UNKNOWN";

    /** Состояния проекта расширения, из-за которых EDT требует обновить приложение; LOADING она пропускает. */
    private static final Set<String> EXTENSION_BLOCKERS =
        Set.of(INCREMENTAL_UPDATE_REQUIRED, FULL_UPDATE_REQUIRED, UNKNOWN);

    /** Итоги, при которых {@code ensureUpdated} показывает окно «Обновление приложения». */
    private static final Set<String> DIALOG_STATES = Set.of(UNKNOWN, INCREMENTAL_UPDATE_REQUIRED, FULL_UPDATE_REQUIRED);

    private UpdateStateRule() {}

    /**
     * Состояние одного проекта — как {@code getUpdateState(InfobaseEqualityState, boolean notConnected)}: не
     * подключён → UNKNOWN; EQUAL → UPDATED; LOADING → BEING_UPDATED; NOT_EQUAL → INCREMENTAL_UPDATE_REQUIRED;
     * незнакомое → UNKNOWN. {@code null} — входов не хватает.
     */
    public static String projectState(String equalityState, Boolean connected) {
        if (connected == null) return null;
        if (!connected) return UNKNOWN;
        if (equalityState == null) return null;
        return switch (equalityState) {
            case "EQUAL" -> UPDATED;
            case "LOADING" -> BEING_UPDATED;
            case "NOT_EQUAL" -> INCREMENTAL_UPDATE_REQUIRED;
            default -> UNKNOWN;
        };
    }

    public static String projectState(ProjectSyncState project) {
        return projectState(project.equalityState(), project.connected());
    }

    /**
     * Итог приложения: состояние проекта конфигурации, если оно не UPDATED; иначе INCREMENTAL_UPDATE_REQUIRED, если
     * хоть одно расширение в INCREMENTAL_UPDATE_REQUIRED / FULL_UPDATE_REQUIRED / UNKNOWN; иначе UPDATED.
     * {@code null} — исход зависит от входа, который не получен.
     */
    public static String overall(ProjectSyncState configuration, List<ProjectSyncState> extensions) {
        String main = projectState(configuration);
        if (!UPDATED.equals(main)) return main;
        boolean undecided = false;
        for (ProjectSyncState extension : extensions) {
            String state = projectState(extension);
            if (state == null) {
                undecided = true;
            } else if (EXTENSION_BLOCKERS.contains(state)) {
                return INCREMENTAL_UPDATE_REQUIRED;
            }
        }
        return undecided ? null : UPDATED;
    }

    /**
     * Покажет ли {@code ensureUpdated} окно «Обновление приложения»: UNKNOWN / INCREMENTAL_UPDATE_REQUIRED /
     * FULL_UPDATE_REQUIRED — да; UPDATED — нет, клиент запускается; BEING_UPDATED — нет, запуск отменяется (обновление
     * уже идёт); {@code null} или незнакомое состояние — неизвестно.
     */
    public static Boolean dialogExpected(String applicationState) {
        if (applicationState == null) return null;
        if (DIALOG_STATES.contains(applicationState)) return Boolean.TRUE;
        if (UPDATED.equals(applicationState) || BEING_UPDATED.equals(applicationState)) return Boolean.FALSE;
        return null;
    }

    /**
     * Чем проект мешает запуску без окна (или может помешать: состояние не получено) — пункт {@link #summary};
     * {@code null} — не мешает. Расширение в LOADING EDT пропускает.
     */
    public static String problem(ProjectSyncState project) {
        String name = project.project();
        String state = projectState(project);
        if (state == null) {
            return name + " — состояние не получено" + (project.error() == null ? "" : " (" + project.error() + ")");
        }
        if (UPDATED.equals(state)) return null;
        if (BEING_UPDATED.equals(state)) {
            return ProjectSyncState.CONFIGURATION.equals(project.kind())
                ? name + " — LOADING: синхронизация с базой идёт прямо сейчас" : null;
        }
        if (Boolean.FALSE.equals(project.connected())) {
            return name + " — не подключён к базе (у EDT нет объекта синхронизации проекта)";
        }
        if (!"NOT_EQUAL".equals(project.equalityState())) return name + " — " + project.equalityState();
        if (Boolean.TRUE.equals(project.projectDirty())) {
            return name + " — NOT_EQUAL: файлы проекта не совпадают со снимком синхронизации EDT (или снимка нет)";
        }
        if (Boolean.FALSE.equals(project.projectDirty())) {
            return name + " — NOT_EQUAL при файлах, совпадающих со снимком: у EDT нет соединения проекта именно с этой "
                + "базой (или сбой хранилища состояния — см. журнал EDT)";
        }
        return name + " — NOT_EQUAL";
    }

    /** Пункты {@link #problem} по проекту конфигурации и его расширениям — те, что мешают (или могут помешать). */
    public static List<String> problems(ProjectSyncState configuration, List<ProjectSyncState> extensions) {
        List<String> problems = new ArrayList<>();
        String own = problem(configuration);
        if (own != null) problems.add(own);
        for (ProjectSyncState extension : extensions) {
            String problem = problem(extension);
            if (problem != null) problems.add(problem);
        }
        return problems;
    }

    /**
     * Сводка по-русски: будет ли окно и из-за каких проектов.
     *
     * @param state итог: ответ EDT, а если его нет — {@link #overall}; {@code null} — неизвестен
     */
    public static String summary(String state, String infobase, ProjectSyncState configuration,
                                 List<ProjectSyncState> extensions) {
        List<String> problems = problems(configuration, extensions);
        String list = String.join("; ", problems);
        if (state == null) {
            return "Итог не определён: EDT его не отдала, а данных для расчёта не хватает"
                + (problems.isEmpty() ? "" : ": " + list);
        }
        if (UPDATED.equals(state)) {
            List<String> loading = new ArrayList<>();
            for (ProjectSyncState extension : extensions) {
                if (BEING_UPDATED.equals(projectState(extension))) loading.add(extension.project());
            }
            String others = extensions.isEmpty() ? ""
                : loading.isEmpty() ? " и его проекты расширений (" + extensions.size() + ")"
                : " и его проекты расширений, кроме синхронизирующихся сейчас (" + String.join(", ", loading)
                    + " — EDT их при запуске пропускает),";
            return "Окна при запуске клиента не будет: проект конфигурации" + others + " совпадают с базой"
                + (problems.isEmpty() ? "" : ". Замечания: " + list);
        }
        if (BEING_UPDATED.equals(state)) {
            return "С базой прямо сейчас идёт синхронизация: запуск клиента EDT отменит (обновление уже выполняется)";
        }
        if (!DIALOG_STATES.contains(state)) {
            return "EDT ответила " + state + " — такого состояния в правиле EDT 2026.1 нет, будет ли окно, неизвестно"
                + (problems.isEmpty() ? "" : ". Замечания: " + list);
        }
        StringBuilder text = new StringBuilder("При запуске клиента EDT покажет окно «Обновление приложения»: "
            + "«Конфигурация информационной базы \"" + infobase + "\" не синхронизирована с проектом \""
            + configuration.project() + "\"" + reasonSuffix(state) + "» (итог " + state + ")");
        if (!problems.isEmpty()) text.append(". Причины: ").append(list);
        if (Boolean.FALSE.equals(configuration.connected())) {
            text.append(". Перед этой проверкой запуск сам подключает проект конфигурации к базе (prepare → "
                + "connectInfobase), после подключения итог может быть другим");
        }
        text.append(". Кнопка «Обновить и запустить» заливает проект в базу");
        return text.toString();
    }

    /** Хвост текста окна, как у {@code askUserUpdateDecision}: у UNKNOWN его нет. */
    private static String reasonSuffix(String state) {
        return switch (state) {
            case INCREMENTAL_UPDATE_REQUIRED -> ", требуется загрузка изменённых объектов";
            case FULL_UPDATE_REQUIRED -> ", требуется полная загрузка";
            default -> "";
        };
    }
}
