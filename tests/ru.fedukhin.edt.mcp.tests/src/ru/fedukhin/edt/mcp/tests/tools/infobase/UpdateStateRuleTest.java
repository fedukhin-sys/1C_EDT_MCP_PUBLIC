package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectSyncState;
import ru.fedukhin.edt.mcp.tools.infobase.internal.UpdateStateRule;

/** Правило EDT 2026.1 {@code InfobaseApplicationProvisionDelegate.getUpdateState} и окно {@code ensureUpdated}. */
public class UpdateStateRuleTest {

    private static ProjectSyncState conf(String equality, Boolean connected) {
        return new ProjectSyncState("Demo", ProjectSyncState.CONFIGURATION, equality, connected, null, null);
    }

    private static ProjectSyncState ext(String name, String equality, Boolean connected) {
        return new ProjectSyncState(name, ProjectSyncState.EXTENSION, equality, connected, null, null);
    }

    @Test
    public void projectState_mirrorsEdtTable() {
        assertEquals("UNKNOWN", UpdateStateRule.projectState("EQUAL", false));
        assertEquals("UNKNOWN", UpdateStateRule.projectState(null, false));
        assertEquals("UPDATED", UpdateStateRule.projectState("EQUAL", true));
        assertEquals("BEING_UPDATED", UpdateStateRule.projectState("LOADING", true));
        assertEquals("INCREMENTAL_UPDATE_REQUIRED", UpdateStateRule.projectState("NOT_EQUAL", true));
        assertEquals("UNKNOWN", UpdateStateRule.projectState("SOMETHING_NEW", true));
        assertNull(UpdateStateRule.projectState("EQUAL", null));
        assertNull(UpdateStateRule.projectState(null, true));
    }

    @Test
    public void overall_configurationNotUpdated_decidesAlone() {
        assertEquals("UNKNOWN", UpdateStateRule.overall(conf("EQUAL", false), List.of(ext("E", "EQUAL", true))));
        assertEquals("BEING_UPDATED",
            UpdateStateRule.overall(conf("LOADING", true), List.of(ext("E", "NOT_EQUAL", true))));
        assertEquals("INCREMENTAL_UPDATE_REQUIRED", UpdateStateRule.overall(conf("NOT_EQUAL", true), List.of()));
        assertNull(UpdateStateRule.overall(conf(null, true), List.of(ext("E", "NOT_EQUAL", true))));
    }

    @Test
    public void overall_extensionNotEqualOrDisconnected_requiresIncrementalUpdate() {
        assertEquals("INCREMENTAL_UPDATE_REQUIRED", UpdateStateRule.overall(conf("EQUAL", true),
            List.of(ext("A", "EQUAL", true), ext("B", "NOT_EQUAL", true))));
        assertEquals("INCREMENTAL_UPDATE_REQUIRED", UpdateStateRule.overall(conf("EQUAL", true),
            List.of(ext("A", "EQUAL", false))));
    }

    /** Расширение в LOADING EDT пропускает. */
    @Test
    public void overall_extensionLoading_isSkipped() {
        assertEquals("UPDATED", UpdateStateRule.overall(conf("EQUAL", true), List.of(ext("A", "LOADING", true))));
    }

    @Test
    public void overall_unknownExtension_undecidedUnlessAnotherBlocks() {
        assertNull(UpdateStateRule.overall(conf("EQUAL", true),
            List.of(ext("A", null, true), ext("B", "EQUAL", true))));
        assertEquals("INCREMENTAL_UPDATE_REQUIRED", UpdateStateRule.overall(conf("EQUAL", true),
            List.of(ext("A", "EQUAL", null), ext("B", "NOT_EQUAL", true))));
        assertEquals("UPDATED", UpdateStateRule.overall(conf("EQUAL", true), List.of()));
    }

    @Test
    public void dialogExpected_followsEnsureUpdated() {
        assertEquals(Boolean.TRUE, UpdateStateRule.dialogExpected("UNKNOWN"));
        assertEquals(Boolean.TRUE, UpdateStateRule.dialogExpected("INCREMENTAL_UPDATE_REQUIRED"));
        assertEquals(Boolean.TRUE, UpdateStateRule.dialogExpected("FULL_UPDATE_REQUIRED"));
        assertEquals(Boolean.FALSE, UpdateStateRule.dialogExpected("UPDATED"));
        assertEquals(Boolean.FALSE, UpdateStateRule.dialogExpected("BEING_UPDATED"));
        assertNull(UpdateStateRule.dialogExpected(null));
        assertNull(UpdateStateRule.dialogExpected("SOMETHING_NEW"));
    }

    @Test
    public void problem_explainsEachProject() {
        ProjectSyncState dirty = new ProjectSyncState("Demo.Склад", ProjectSyncState.EXTENSION, "NOT_EQUAL", true,
            true, null);
        ProjectSyncState clean = new ProjectSyncState("Demo.Склад", ProjectSyncState.EXTENSION, "NOT_EQUAL", true,
            false, null);
        ProjectSyncState unknown = new ProjectSyncState("Demo", ProjectSyncState.CONFIGURATION, null, true, null,
            "нет метода");

        assertTrue(UpdateStateRule.problem(dirty), UpdateStateRule.problem(dirty).contains("не совпадают со снимком"));
        assertTrue(UpdateStateRule.problem(clean), UpdateStateRule.problem(clean).contains("нет соединения"));
        assertTrue(UpdateStateRule.problem(conf("EQUAL", false)).contains("не подключён"));
        assertNull(UpdateStateRule.problem(conf("EQUAL", true)));
        assertNull("расширение в LOADING EDT пропускает", UpdateStateRule.problem(ext("A", "LOADING", true)));
        assertTrue(UpdateStateRule.problem(conf("LOADING", true)).contains("LOADING"));
        assertTrue(UpdateStateRule.problem(unknown).contains("нет метода"));
    }

    @Test
    public void summary_dialog_quotesEdtWindowAndNamesProjects() {
        ProjectSyncState sklad = new ProjectSyncState("Demo.Склад", ProjectSyncState.EXTENSION, "NOT_EQUAL", true,
            true, null);

        String text = UpdateStateRule.summary("INCREMENTAL_UPDATE_REQUIRED", "DemoIB", conf("EQUAL", true),
            List.of(sklad));

        assertTrue(text, text.contains("«Обновление приложения»"));
        assertTrue(text, text.contains("Конфигурация информационной базы \"DemoIB\" не синхронизирована с проектом "
            + "\"Demo\", требуется загрузка изменённых объектов"));
        assertTrue(text, text.contains("Demo.Склад — NOT_EQUAL"));
        assertTrue(text, text.contains("«Обновить и запустить» заливает проект в базу"));
    }

    @Test
    public void summary_otherOutcomes() {
        assertTrue(UpdateStateRule.summary("UPDATED", "DemoIB", conf("EQUAL", true), List.of(ext("A", "EQUAL", true)))
            .startsWith("Окна при запуске клиента не будет"));
        assertTrue(UpdateStateRule.summary("BEING_UPDATED", "DemoIB", conf("LOADING", true), List.of())
            .contains("отменит"));
        assertTrue(UpdateStateRule.summary(null, "DemoIB", conf(null, true), List.of())
            .startsWith("Итог не определён"));
        assertTrue(UpdateStateRule.summary("SOMETHING_NEW", "DemoIB", conf("EQUAL", true), List.of())
            .contains("SOMETHING_NEW"));
    }

    /** Расширение в LOADING EDT при запуске пропускает — сводка не выдаёт его за совпадающее с базой. */
    @Test
    public void summary_updated_namesLoadingExtensions() {
        String text = UpdateStateRule.summary("UPDATED", "DemoIB", conf("EQUAL", true),
            List.of(ext("Demo.A", "EQUAL", true), ext("Demo.B", "LOADING", true)));

        assertTrue(text, text.startsWith("Окна при запуске клиента не будет"));
        assertTrue(text, text.contains("кроме синхронизирующихся сейчас (Demo.B"));
    }

    @Test
    public void summary_disconnectedConfiguration_mentionsConnectOnLaunch() {
        String text = UpdateStateRule.summary("UNKNOWN", "DemoIB", conf("NOT_EQUAL", false), List.of());

        assertTrue(text, text.contains("не подключён"));
        assertTrue(text, text.contains("prepare"));
        assertFalse("у UNKNOWN окно без хвоста о загрузке", text.contains("требуется"));
    }
}
