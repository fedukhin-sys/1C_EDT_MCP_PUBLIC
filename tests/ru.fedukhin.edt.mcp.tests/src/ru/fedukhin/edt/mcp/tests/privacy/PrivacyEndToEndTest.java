package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import com.google.inject.Guice;
import com.google.inject.Injector;
import java.util.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.internal.di.McpRuntimeModule;
import ru.fedukhin.edt.mcp.core.internal.di.McpHttpModule;
import ru.fedukhin.edt.mcp.core.privacy.IPrivacyFilter;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyState;

/**
 * Сквозной интеграционный тест: реальный Guice-инжектор (core + http модули),
 * реальный {@link ru.fedukhin.edt.mcp.core.privacy.PrivacyRedactor} (не заглушка),
 * и разделяемый процесс-синглтон {@link PrivacyState} как гейт «база без реальных ПДн».
 *
 * <p>Класс называется *Test (а не *IT), чтобы попасть в дефолтные include-паттерны
 * tycho-surefire и реально исполниться в OSGi-рантайме сборки; суффикс *IT в этом
 * репозитории закреплён за смоук-тестами, которые намеренно исключены из прогона
 * (см. {@code EssEventLogSmokeIT}).
 */
public class PrivacyEndToEndTest {

    @SuppressWarnings("unchecked")
    @Test public void realFilterMasksSensitiveVariableAndHonoursSharedFlag() {
        Injector injector = Guice.createInjector(new McpRuntimeModule(), new McpHttpModule());
        IPrivacyFilter filter = injector.getInstance(IPrivacyFilter.class);

        // ПДн по имени переменной — реальный PrivacyRedactor + AttributeNameDictionary + Pseudonymizer
        List<Map<String,Object>> vars = new ArrayList<>();
        vars.add(new LinkedHashMap<>(Map.of("name","СНИЛС","type","Строка","value","112-233-445 95")));
        Object masked = filter.redact("get_variables", Map.of("name","E2EBase"), vars);
        String v = (String) ((List<Map<String,Object>>) masked).get(0).get("value");
        assertNotEquals("112-233-445 95", v); // значение обезличено

        // shared PrivacyState: пометить базу как «без реальных ПДн» → фильтр пропускает
        PrivacyState.flags().setFlag("E2EBase", false);
        List<Map<String,Object>> vars2 = new ArrayList<>();
        vars2.add(new LinkedHashMap<>(Map.of("name","СНИЛС","type","Строка","value","112-233-445 95")));
        Object passthrough = filter.redact("get_variables", Map.of("name","E2EBase"), vars2);
        assertEquals("112-233-445 95",
            ((List<Map<String,Object>>) passthrough).get(0).get("value")); // не тронуто

        // вернуть флаг, чтобы не влиять на другие тесты (PrivacyState — процесс-синглтон)
        PrivacyState.flags().setFlag("E2EBase", true);
    }
}
