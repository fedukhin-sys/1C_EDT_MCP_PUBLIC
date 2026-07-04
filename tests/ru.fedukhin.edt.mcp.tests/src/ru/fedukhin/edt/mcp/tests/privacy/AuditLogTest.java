package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.AuditLog;
import ru.fedukhin.edt.mcp.core.privacy.Sensitivity;

public class AuditLogTest {
    @Test public void recordsCountsNotValues() {
        AuditLog log = new AuditLog();
        log.record("get_variables", "Demo", Sensitivity.PERSONAL, "Справочник.ФизическиеЛица", 3);
        List<Map<String,Object>> recent = log.recent(10);
        assertEquals(1, recent.size());
        assertEquals(3, recent.get(0).get("count"));
        assertEquals("PERSONAL", recent.get(0).get("sensitivity"));
        // гарантия: ни одно поле не содержит исходного значения ПДн (только объект/тип/счётчик)
        assertFalse(recent.get(0).containsKey("value"));
    }
}
