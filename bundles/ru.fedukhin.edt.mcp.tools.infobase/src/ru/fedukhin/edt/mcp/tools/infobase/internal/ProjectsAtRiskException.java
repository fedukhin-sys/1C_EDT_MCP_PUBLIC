package ru.fedukhin.edt.mcp.tools.infobase.internal;

import java.util.List;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Отказ обновить проект из базы без {@code discardProjectChanges} (fix round 5, O1): EDT не подтвердила, что в
 * проекте нет изменений, которых нет в базе. Кроме текста несёт сами пункты ({@link #atRisk()}, формат
 * {@link SyncV2#atRiskMessage}): сценарий, который до обновления проекта уже загрузил в базу {@code .dt} или
 * {@code .cfe}, заменяет общий текст своим (fix round 6b; с fix round 9 — через {@link AfterLoad#refusal}) — общий
 * совет «сначала залейте правки (deploy_project)» после загрузки заменил бы только что загруженную конфигурацию
 * содержимым проекта.
 *
 * <p>Только свои типы и JDK: исключение бросает {@code ProjectFromInfobaseUpdater}, класс, который биндится в Guice.
 */
public class ProjectsAtRiskException extends ToolException {

    private static final long serialVersionUID = 1L;

    private final List<String> atRisk;

    /** Общий текст {@link SyncV2#atRiskMessage}: до отказа ничего разрушительного не было (примитив). */
    public ProjectsAtRiskException(List<String> atRisk) {
        this(atRisk, SyncV2.atRiskMessage(atRisk));
    }

    public ProjectsAtRiskException(List<String> atRisk, String message) {
        super(message);
        this.atRisk = List.copyOf(atRisk);
    }

    /** Пункты «проект (почему)» — те же, что в тексте общего отказа. */
    public List<String> atRisk() {
        return atRisk;
    }
}
