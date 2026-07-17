package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.*;

public class PrivacyRedactorTest {

    private PrivacyRedactor redactor(PiiCatalog cat) {
        return new PrivacyRedactor(
            () -> cat,
            new Pseudonymizer("k".getBytes(StandardCharsets.UTF_8)),
            new InfobaseFlagStore(new HashMap<>()),   // дефолт true
            new AuditLog());
    }

    @SuppressWarnings("unchecked")
    @Test public void masksVariableOfSensitiveRefType() {
        PiiCatalog cat = PiiCatalog.builder()
            .object("Справочник.Контрагенты", Sensitivity.COUNTERPARTY).build();
        List<Map<String,Object>> vars = new ArrayList<>();
        vars.add(new LinkedHashMap<>(Map.of("name","Контр","type","СправочникСсылка.Контрагенты","value","ООО Ромашка")));
        Object out = redactor(cat).redact("get_variables", null, vars);
        Map<String,Object> m = ((List<Map<String,Object>>) out).get(0);
        assertEquals("СправочникСсылка.Контрагенты", m.get("type")); // тип не трогаем
        assertTrue(((String) m.get("value")).startsWith("Контрагент#"));
        assertFalse(((String) m.get("value")).contains("Ромашка"));
    }

    @SuppressWarnings("unchecked")
    @Test public void masksVariableByAttributeName() {
        List<Map<String,Object>> vars = new ArrayList<>();
        vars.add(new LinkedHashMap<>(Map.of("name","СНИЛС","type","Строка","value","112-233-445 95")));
        Object out = redactor(PiiCatalog.builder().build()).redact("get_variables", null, vars);
        String v = (String) ((List<Map<String,Object>>) out).get(0).get("value");
        assertTrue(v.startsWith("Физлицо#"));
    }

    @SuppressWarnings("unchecked")
    @Test public void eventLogUserAlwaysPseudonymised() {
        Map<String,Object> ev = new LinkedHashMap<>();
        ev.put("user","ИвановИИ"); ev.put("comment","звонил ivan@mail.ru");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("events", new ArrayList<>(List.of(ev)));
        Object out = redactor(PiiCatalog.builder().build()).redact("query_event_log", "Demo", result);
        Map<String,Object> e = ((List<Map<String,Object>>)((Map<String,Object>) out).get("events")).get(0);
        assertTrue(((String) e.get("user")).startsWith("Физлицо#"));
        assertFalse(((String) e.get("comment")).contains("ivan@mail.ru")); // regex-слой
    }

    @SuppressWarnings("unchecked")
    @Test public void skipsWhenInfobaseFlaggedNoPii() {
        InfobaseFlagStore flags = new InfobaseFlagStore(new HashMap<>());
        flags.setFlag("TestBase", false);
        PrivacyRedactor r = new PrivacyRedactor(
            () -> PiiCatalog.builder().build(),
            new Pseudonymizer("k".getBytes(StandardCharsets.UTF_8)), flags, new AuditLog());
        Map<String,Object> ev = new LinkedHashMap<>(); ev.put("user","ИвановИИ");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("events", new ArrayList<>(List.of(ev)));
        Object out = r.redact("query_event_log", "TestBase", result);
        assertEquals("ИвановИИ",
            ((List<Map<String,Object>>)((Map<String,Object>) out).get("events")).get(0).get("user"));
    }

    /**
     * Запись ЖР без metadata (объект не в каталоге) — regex-сеть обязана дойти до data.value:
     * там представления ссылок, ФИО и телефоны из журнала регистрации.
     */
    @SuppressWarnings("unchecked")
    @Test public void masksEventLogDataValueWithoutMetadata() {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("type", "S");
        data.put("value", "Иванов, тел. +7 912 345-67-89");
        Map<String,Object> ev = new LinkedHashMap<>();
        ev.put("user", "ИвановИИ");
        ev.put("data", data);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("events", new ArrayList<>(List.of(ev)));

        Object out = redactor(PiiCatalog.builder().build()).redact("query_event_log", "Demo", result);

        Map<String,Object> e = ((List<Map<String,Object>>)((Map<String,Object>) out).get("events")).get(0);
        String dv = (String) ((Map<String,Object>) e.get("data")).get("value");
        assertFalse("телефон утёк через data.value: " + dv, dv.contains("912 345-67-89"));
    }

    @SuppressWarnings("unchecked")
    @Test public void masksEvaluateErrorViaRegex() {
        Map<String,Object> res = new LinkedHashMap<>();
        res.put("ok", false);
        res.put("error", "Ошибка: адрес ivan@mail.ru не найден");
        Object out = redactor(PiiCatalog.builder().build()).redact("evaluate", null, res);
        String err = (String) ((Map<String,Object>) out).get("error");
        assertFalse(err.contains("ivan@mail.ru"));
        assertTrue(err.contains("Ошибка")); // не-ПДн текст сохранён
    }
}
