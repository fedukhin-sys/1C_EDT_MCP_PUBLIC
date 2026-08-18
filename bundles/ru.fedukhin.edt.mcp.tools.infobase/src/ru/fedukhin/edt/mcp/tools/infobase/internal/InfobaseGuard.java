package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Защита от деплоя и прогона тестов не в ту информационную базу.
 *
 * <p><b>Зачем.</b> Список баз в 1С — один на пользователя
 * ({@code %APPDATA%\1C\1CEStart\ibases.v8i}), поэтому любая сессия видит любую
 * базу машины, а инструменты резолвят цель по имени. При нескольких параллельных
 * сессиях одна опечатка отправляет расширение одного проекта в чужую базу — без
 * единой ошибки, из правильной рабочей области, правильным инструментом.
 *
 * <p>Ассоциация проекта с базой в EDT существует и ставится инструментом
 * {@code associate_infobase}, но операциями до сих пор игнорировалась.
 */
public final class InfobaseGuard {

    private InfobaseGuard() {}

    /**
     * @param associated имена баз, связанных с проектом. Различаются два случая:
     *     {@code Optional.empty()} — спросить не удалось (менеджер ассоциаций
     *     недоступен), тогда молчим; пустой набор внутри — ассоциации нет, тогда
     *     предупреждаем. Смешивать их нельзя: иначе каждый деплой в среде без
     *     менеджера тащил бы предупреждение, которого пользователь не заказывал.
     * @param allowForeign явное разрешение работать с непривязанной базой
     * @return предупреждение для ответа инструмента либо пусто, если всё сошлось
     * @throws ToolException ассоциация есть, база в неё не входит, обход не запрошен
     */
    public static Optional<String> check(String projectName, String requestedInfobase,
                                         Optional<Set<String>> associated, boolean allowForeign)
            throws ToolException {
        if (associated == null || associated.isEmpty()) return Optional.empty();

        Collection<String> names = associated.get();
        if (names.isEmpty()) {
            return Optional.of("проект '" + projectName + "' не связан ни с одной информационной базой — "
                + "принадлежность базы '" + requestedInfobase + "' не проверена; "
                + "свяжите проект вызовом associate_infobase");
        }
        if (names.contains(requestedInfobase)) return Optional.empty();

        String linked = String.join(", ", names);
        if (allowForeign) {
            return Optional.of("проект '" + projectName + "' связан с базами [" + linked
                + "], а операция идёт в '" + requestedInfobase
                + "' — выполнено по явному allowForeignInfobase");
        }
        throw new ToolException("проект '" + projectName + "' связан с информационными базами ["
            + linked + "], а запрошена '" + requestedInfobase + "'. Операция отменена. "
            + "Если это намеренно — повторите с allowForeignInfobase: true");
    }

    /**
     * Имена баз, связанных с проектом.
     *
     * <p>{@code Optional.empty()} означает «спросить не удалось» — менеджер
     * ассоциаций недоступен либо сигнатура разошлась на ветке EDT 2023.x. Это
     * принципиально отличается от «ассоциации нет» (пустой набор внутри
     * {@code Optional}): в первом случае сверка невозможна и молчим, во втором —
     * предупреждаем, что защита не работает. Падать нельзя ни в одном из них:
     * недоступность менеджера не должна блокировать деплой.
     */
    public static Optional<Set<String>> associatedNames(IInfobaseAssociationManager assoc,
                                                        IProject project) {
        if (assoc == null || project == null) return Optional.empty();
        try {
            Optional<IInfobaseAssociation> association = assoc.getAssociation(project);
            Set<String> names = new LinkedHashSet<>();
            if (association.isEmpty()) return Optional.of(names);
            Collection<InfobaseReference> refs = association.get().getInfobases();
            if (refs != null) {
                for (InfobaseReference ref : refs) {
                    if (ref != null && ref.getName() != null) names.add(ref.getName());
                }
            }
            return Optional.of(names);
        } catch (Exception | LinkageError e) {
            return Optional.empty();
        }
    }
}
