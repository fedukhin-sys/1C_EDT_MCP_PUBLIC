package ru.fedukhin.edt.mcp.tools.tests.internal;

import jakarta.inject.Singleton;
import java.util.regex.Pattern;

@Singleton
public class TestModuleHeuristic {

    private static final Pattern RU_PROC = Pattern.compile("(?im)^\\s*Процедура\\s+ИсполняемыеСценарии\\s*\\(");
    private static final Pattern EN_PROC = Pattern.compile("(?im)^\\s*Procedure\\s+ExecutableScenarios\\s*\\(");

    /** Returns "ru" / "en" / null based on module name and content. */
    public String detectLanguage(String moduleName, String moduleText) {
        boolean nameIsRu = moduleName != null && (moduleName.contains("Тесты") || moduleName.startsWith("Тест"));
        boolean nameIsEn = moduleName != null && (moduleName.contains("Tests") || moduleName.startsWith("Test"));
        boolean bodyHasRu = moduleText != null && RU_PROC.matcher(moduleText).find();
        boolean bodyHasEn = moduleText != null && EN_PROC.matcher(moduleText).find();
        if (nameIsRu || bodyHasRu) return "ru";
        if (nameIsEn || bodyHasEn) return "en";
        return null;
    }

    public boolean isTestModule(String moduleName, String moduleText) {
        return detectLanguage(moduleName, moduleText) != null;
    }

    public boolean hasExecutableScenarios(String moduleText) {
        return moduleText != null && (RU_PROC.matcher(moduleText).find() || EN_PROC.matcher(moduleText).find());
    }
}
