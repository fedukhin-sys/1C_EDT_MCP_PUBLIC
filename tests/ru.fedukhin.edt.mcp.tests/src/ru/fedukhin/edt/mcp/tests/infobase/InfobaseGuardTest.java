package ru.fedukhin.edt.mcp.tests.infobase;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseGuard;

/**
 * Список информационных баз общий на пользователя, поэтому deploy_project и
 * run_tests могут увести расширение в чужую базу по одной опечатке. Сверка с
 * ассоциацией проекта — единственное, что от этого защищает: маршрутизация по
 * портам тут бессильна, ошибка происходит внутри правильной инстанции.
 */
public class InfobaseGuardTest {

    private static Optional<Set<String>> linked(String... names) {
        return Optional.of(new LinkedHashSet<>(List.of(names)));
    }

    @Test
    public void matchingAssociation_passesWithoutWarning() throws Exception {
        Optional<String> w = InfobaseGuard.check("DemoExt", "DemoBase", linked("DemoBase"), false);
        assertTrue("совпадение — молча пропускаем", w.isEmpty());
    }

    /** Проект может быть связан с несколькими базами — любая из них законна. */
    @Test
    public void anyAssociatedInfobase_passes() throws Exception {
        Optional<String> w = InfobaseGuard.check("DemoExt", "DemoBase2", linked("DemoBase", "DemoBase2"), false);
        assertTrue(w.isEmpty());
    }

    @Test
    public void mismatchedAssociation_isRefused() {
        try {
            InfobaseGuard.check("DemoExt", "OtherBase", linked("DemoBase"), false);
            fail("ожидался отказ: база не та, с которой связан проект");
        } catch (ToolException e) {
            assertTrue("в тексте должны быть обе базы, было: " + e.getMessage(),
                    e.getMessage().contains("OtherBase") && e.getMessage().contains("DemoBase"));
            assertTrue("должен подсказывать обходной путь, было: " + e.getMessage(),
                    e.getMessage().contains("allowForeignInfobase"));
        }
    }

    @Test
    public void mismatchedAssociation_passesWhenExplicitlyAllowed() throws Exception {
        Optional<String> w = InfobaseGuard.check("DemoExt", "OtherBase", linked("DemoBase"), true);
        assertTrue("при явном разрешении должно остаться предупреждение", w.isPresent());
        assertTrue(w.get().contains("OtherBase"));
    }

    /**
     * Блокировать при незаданной ассоциации нельзя: это сломало бы существующие
     * рабочие процессы, где связь просто не проставлена. Но предупредить надо —
     * пользователь должен знать, что защита не работает.
     */
    @Test
    public void emptyAssociation_passesWithWarning() throws Exception {
        Optional<String> w = InfobaseGuard.check("DemoExt", "OtherBase", Optional.of(Set.of()), false);
        assertTrue(w.isPresent());
        assertTrue("подсказывает, чем починить, было: " + w.get(),
                w.get().contains("associate_infobase"));
    }

    /**
     * «Спросить не удалось» ≠ «ассоциации нет». В первом случае молчим: иначе
     * каждый деплой в среде без менеджера ассоциаций тащил бы предупреждение,
     * которого пользователь не заказывал.
     */
    @Test
    public void unknownAssociation_isSilent() throws Exception {
        assertTrue(InfobaseGuard.check("DemoExt", "OtherBase", Optional.empty(), false).isEmpty());
        assertTrue(InfobaseGuard.check("DemoExt", "OtherBase", null, false).isEmpty());
    }

    /** Недоступный менеджер ассоциаций не должен ронять операцию. */
    @Test
    public void associatedNames_onNullManager_isUnknown() {
        assertTrue("это именно «неизвестно», а не «ассоциаций нет»",
                InfobaseGuard.associatedNames(null, null).isEmpty());
    }
}
