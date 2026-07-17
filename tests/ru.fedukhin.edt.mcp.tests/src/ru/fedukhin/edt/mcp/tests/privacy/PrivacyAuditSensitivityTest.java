package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.AuditLog;
import ru.fedukhin.edt.mcp.core.privacy.InfobaseFlagStore;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalog;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyRedactor;
import ru.fedukhin.edt.mcp.core.privacy.Pseudonymizer;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

/**
 * Журнал обезличивания обязан писать реально сработавшую категорию.
 *
 * <p>Раньше в него всегда попадало {@code PERSONAL}: запись о сокрытии спец-категории или
 * биометрии выглядела как обычная псевдонимизация ФИО, то есть журнал 152-ФЗ вводил в заблуждение
 * о том, что именно скрывалось.
 */
public class PrivacyAuditSensitivityTest {

    private static AuditLog audit;

    private static PrivacyRedactor redactor(PiiCatalog cat) {
        audit = new AuditLog();
        return new PrivacyRedactor(
            () -> cat,
            new Pseudonymizer("k".getBytes(StandardCharsets.UTF_8)),
            new InfobaseFlagStore(new HashMap<>()),
            audit);
    }

    private static List<Map<String, Object>> vars(Map<String, Object> var) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(new LinkedHashMap<>(var));
        return list;
    }

    private static String loggedSensitivity() {
        List<Map<String, Object>> recent = audit.recent(10);
        assertEquals("ожидалась ровно одна запись аудита", 1, recent.size());
        return (String) recent.get(0).get("sensitivity");
    }

    @Test
    public void specialCategory_isLoggedAsSpecial_notPersonal() {
        PiiCatalog cat = PiiCatalog.builder()
            .object("Справочник.Диагнозы", Sensitivity.SPECIAL).build();

        redactor(cat).redact("get_variables", null, vars(Map.of(
            "name", "Диагноз", "type", "СправочникСсылка.Диагнозы", "value", "F20.0")));

        assertEquals(Sensitivity.SPECIAL.name(), loggedSensitivity());
    }

    @Test
    public void counterparty_isLoggedAsCounterparty() {
        PiiCatalog cat = PiiCatalog.builder()
            .object("Справочник.Контрагенты", Sensitivity.COUNTERPARTY).build();

        redactor(cat).redact("get_variables", null, vars(Map.of(
            "name", "Контр", "type", "СправочникСсылка.Контрагенты", "value", "ООО Ромашка")));

        assertEquals(Sensitivity.COUNTERPARTY.name(), loggedSensitivity());
    }

    /** Из нескольких фактов в журнал идёт самый строгий. */
    @Test
    public void mixedSensitivities_logTheStrictest() {
        PiiCatalog cat = PiiCatalog.builder()
            .object("Справочник.Контрагенты", Sensitivity.COUNTERPARTY)
            .object("Справочник.Диагнозы", Sensitivity.SPECIAL)
            .build();
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(new LinkedHashMap<>(Map.of(
            "name", "Контр", "type", "СправочникСсылка.Контрагенты", "value", "ООО Ромашка")));
        list.add(new LinkedHashMap<>(Map.of(
            "name", "Диагноз", "type", "СправочникСсылка.Диагнозы", "value", "F20.0")));

        redactor(cat).redact("get_variables", null, list);

        assertEquals(Sensitivity.SPECIAL.name(), loggedSensitivity());
        assertEquals(2, audit.recent(10).get(0).get("count"));
    }

    /** Сработала только content-regex-сеть: категория неизвестна — fail-closed считаем ПДн. */
    @Test
    public void regexOnlyHit_isLoggedAsPersonal() {
        redactor(PiiCatalog.builder().build()).redact("get_stack", null,
            List.of("вызов с телефоном +7 916 123-45-67"));

        assertEquals(Sensitivity.PERSONAL.name(), loggedSensitivity());
    }
}
