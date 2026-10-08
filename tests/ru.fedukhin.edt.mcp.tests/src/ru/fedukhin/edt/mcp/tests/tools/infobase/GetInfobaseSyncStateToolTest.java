package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.GetInfobaseSyncStateTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtApplicationState;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncSnapshot;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectSyncState;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncStateProbe;

public class GetInfobaseSyncStateToolTest {

    private final InfobaseTargets targets = mock(InfobaseTargets.class);
    private final InfobaseRegistry registry = mock(InfobaseRegistry.class);
    private final IV8ProjectManager projects = mock(IV8ProjectManager.class);
    private final SyncStateProbe probe = mock(SyncStateProbe.class);
    private final IProject conf = mock(IProject.class);
    private final InfobaseReference ib = mock(InfobaseReference.class);
    private final UUID id = UUID.randomUUID();

    private GetInfobaseSyncStateTool tool() throws Exception {
        when(conf.getName()).thenReturn("Demo");
        when(conf.exists()).thenReturn(true);
        when(conf.isOpen()).thenReturn(true);
        when(ib.getName()).thenReturn("DemoIB");
        when(ib.getUuid()).thenReturn(id);
        when(targets.openProject("Demo")).thenReturn(conf);
        IConfigurationProject v8 = mock(IConfigurationProject.class);
        when(projects.getProject(conf)).thenReturn(v8);
        when(targets.resolve(conf, null, false)).thenReturn(new InfobaseTargets.Target(ib, null));
        when(probe.extensionProjects(conf)).thenReturn(List.of());
        when(probe.read(conf, ProjectSyncState.CONFIGURATION, ib))
            .thenReturn(new ProjectSyncState("Demo", "configuration", "EQUAL", true, false, null));
        when(probe.applicationState(conf, ib)).thenReturn(new EdtApplicationState.Result("UPDATED", null));
        return new GetInfobaseSyncStateTool(targets, registry, projects, probe);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void schema_projectRequired_optionsDeclared() throws Exception {
        Map<String, Object> schema = tool().inputSchema();

        assertEquals(List.of("project"), schema.get("required"));
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertEquals(List.of("project", "infobase", "details", "maxFiles"), List.copyOf(properties.keySet()));
        assertEquals("get_infobase_sync_state", tool().name());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void upToDate_noDialog_noDetails() throws Exception {
        Map<String, Object> out = tool().call(Map.of("project", "Demo"));

        assertEquals("Demo", out.get("project"));
        assertEquals("DemoIB", out.get("infobase"));
        assertEquals(id.toString(), out.get("infobaseUuid"));
        assertEquals("UPDATED", out.get("applicationUpdateState"));
        assertEquals("UPDATED", out.get("computedUpdateState"));
        assertEquals(Boolean.FALSE, out.get("launchDialogExpected"));
        assertTrue(String.valueOf(out.get("summary")).startsWith("Окна при запуске клиента не будет"));
        assertFalse(out.containsKey("requestedProject"));
        assertFalse(out.containsKey("warning"));
        assertFalse(out.containsKey("applicationUpdateStateError"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("projects");
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).containsKey("details"));
        verify(probe, never()).snapshot(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void extensionArgument_checksParentConfiguration() throws Exception {
        GetInfobaseSyncStateTool tool = tool();
        IProject sklad = mock(IProject.class);
        when(sklad.getName()).thenReturn("Demo.Склад");
        when(targets.openProject("Demo.Склад")).thenReturn(sklad);
        IExtensionProject v8 = mock(IExtensionProject.class);
        when(v8.getParentProject()).thenReturn(conf);
        when(projects.getProject(sklad)).thenReturn(v8);
        when(probe.extensionProjects(conf)).thenReturn(List.of(sklad));
        when(probe.read(sklad, ProjectSyncState.EXTENSION, ib))
            .thenReturn(new ProjectSyncState("Demo.Склад", "extension", "NOT_EQUAL", true, true, null));
        when(probe.applicationState(conf, ib))
            .thenReturn(new EdtApplicationState.Result("INCREMENTAL_UPDATE_REQUIRED", null));

        Map<String, Object> out = tool.call(Map.of("project", "Demo.Склад"));

        assertEquals("Demo", out.get("project"));
        assertEquals("Demo.Склад", out.get("requestedProject"));
        assertEquals(Boolean.TRUE, out.get("launchDialogExpected"));
        String summary = String.valueOf(out.get("summary"));
        assertTrue(summary, summary.contains("Demo.Склад — NOT_EQUAL"));
        assertTrue(summary, summary.contains("details=true"));
        assertEquals(2, ((List<Object>) out.get("projects")).size());
    }

    /**
     * Несвязанная база: приложения EDT для пары нет — окна при запуске для неё не бывает, итог EDT не спрашивается,
     * {@code launchDialogExpected = null}; правило — только справочно.
     */
    @Test
    public void foreignInfobase_noEdtApplication_noDialogVerdict() throws Exception {
        GetInfobaseSyncStateTool tool = tool();
        InfobaseReference other = mock(InfobaseReference.class);
        when(other.getName()).thenReturn("Other");
        when(registry.findByName("Other")).thenReturn(Optional.of(other));
        when(targets.associatedInfobases(conf)).thenReturn(List.of("DemoIB"));
        when(probe.read(conf, ProjectSyncState.CONFIGURATION, other))
            .thenReturn(new ProjectSyncState("Demo", "configuration", "NOT_EQUAL", true, false, null));

        Map<String, Object> out = tool.call(Map.of("project", "Demo", "infobase", "Other"));

        assertTrue(String.valueOf(out.get("warning")).contains("не связан с базой 'Other'"));
        assertTrue(String.valueOf(out.get("applicationUpdateStateError")).contains("не связана"));
        assertEquals("INCREMENTAL_UPDATE_REQUIRED", out.get("computedUpdateState"));
        assertTrue(out.containsKey("launchDialogExpected"));
        assertNull(out.get("launchDialogExpected"));
        String summary = String.valueOf(out.get("summary"));
        assertTrue(summary, summary.startsWith("База 'Other' не связана с проектом 'Demo'"));
        verify(probe, never()).applicationState(any(), any());
        verify(targets, never()).resolve(any(), any(), anyBoolean());
    }

    /** Связи проекта прочитать не удалось — это не отказ: предупреждение, а итог EDT спрашивается как обычно. */
    @Test
    public void associationsUnreadable_warnsAndAsksEdt() throws Exception {
        GetInfobaseSyncStateTool tool = tool();
        when(registry.findByName("DemoIB")).thenReturn(Optional.of(ib));
        when(targets.associatedInfobases(conf)).thenThrow(new ToolException("не удалось прочитать связи проекта Demo"));

        Map<String, Object> out = tool.call(Map.of("project", "Demo", "infobase", "DemoIB"));

        assertTrue(String.valueOf(out.get("warning")).contains("не удалось проверить"));
        assertEquals("UPDATED", out.get("applicationUpdateState"));
        assertEquals(Boolean.FALSE, out.get("launchDialogExpected"));
    }

    @Test
    public void extensionListFailure_refusedWithReason() throws Exception {
        GetInfobaseSyncStateTool tool = tool();
        when(probe.extensionProjects(conf)).thenThrow(new IllegalStateException("модель проектов не загружена"));
        try {
            tool.call(Map.of("project", "Demo"));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("модель проектов не загружена"));
        }
    }

    @Test
    public void unknownInfobase_refused() throws Exception {
        GetInfobaseSyncStateTool tool = tool();
        when(registry.findByName("Нет")).thenReturn(Optional.empty());
        try {
            tool.call(Map.of("project", "Demo", "infobase", "Нет"));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not found"));
        }
    }

    @Test
    public void externalObjectsProject_refused() throws Exception {
        GetInfobaseSyncStateTool tool = tool();
        IExternalObjectProject v8 = mock(IExternalObjectProject.class);
        when(projects.getProject(conf)).thenReturn(v8);
        try {
            tool.call(Map.of("project", "Demo"));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("не проект конфигурации и не проект расширения"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void details_attachPerProject() throws Exception {
        GetInfobaseSyncStateTool tool = tool();
        Map<String, byte[]> files = Map.of("src/a.bsl", SignatureDiffTest.sig(1));
        when(probe.snapshot(conf, ib, List.of())).thenReturn(new EdtSyncSnapshot.Read(
            Map.of("Demo", new EdtSyncSnapshot.Recorded(files, 0L)), null,
            Map.of("Demo", new EdtSyncSnapshot.Recorded(files, 0L)), null));
        when(probe.currentSignatures(conf)).thenReturn(new SyncStateProbe.Current(files, null));

        Map<String, Object> out = tool.call(Map.of("project", "Demo", "details", true, "maxFiles", 5));

        Map<String, Object> details = (Map<String, Object>) ((List<Map<String, Object>>) out.get("projects")).get(0)
            .get("details");
        assertEquals(1, details.get("currentFiles"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) details.get("memory")).get("matches"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) details.get("disk")).get("sameAsMemory"));
    }

    /** Детали — у каждой строки, у расширения — против его записи в снимке (ключ — имя проекта расширения). */
    @Test
    @SuppressWarnings("unchecked")
    public void details_withExtension_perProjectRows() throws Exception {
        GetInfobaseSyncStateTool tool = tool();
        IProject sklad = mock(IProject.class);
        when(sklad.getName()).thenReturn("Demo.Склад");
        when(probe.extensionProjects(conf)).thenReturn(List.of(sklad));
        when(probe.read(sklad, ProjectSyncState.EXTENSION, ib))
            .thenReturn(new ProjectSyncState("Demo.Склад", "extension", "NOT_EQUAL", true, true, null));
        when(probe.applicationState(conf, ib))
            .thenReturn(new EdtApplicationState.Result("INCREMENTAL_UPDATE_REQUIRED", null));
        Map<String, byte[]> confFiles = Map.of("src/a.bsl", SignatureDiffTest.sig(1));
        when(probe.snapshot(conf, ib, List.of("Demo.Склад"))).thenReturn(new EdtSyncSnapshot.Read(Map.of(
            "Demo", new EdtSyncSnapshot.Recorded(confFiles, 0L),
            "Demo.Склад", new EdtSyncSnapshot.Recorded(Map.of("src/x.bsl", SignatureDiffTest.sig(5)), 0L)),
            null, null, "не запрашивался"));
        when(probe.currentSignatures(conf)).thenReturn(new SyncStateProbe.Current(confFiles, null));
        when(probe.currentSignatures(sklad))
            .thenReturn(new SyncStateProbe.Current(Map.of("src/x.bsl", SignatureDiffTest.sig(6)), null));

        Map<String, Object> out = tool.call(Map.of("project", "Demo", "details", true));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("projects");
        Map<String, Object> confMemory = (Map<String, Object>) ((Map<String, Object>) rows.get(0).get("details"))
            .get("memory");
        Map<String, Object> skladMemory = (Map<String, Object>) ((Map<String, Object>) rows.get(1).get("details"))
            .get("memory");
        assertEquals(Boolean.TRUE, confMemory.get("matches"));
        assertEquals(Boolean.FALSE, skladMemory.get("matches"));
        assertEquals(1, skladMemory.get("changedCount"));
    }

    @Test
    public void edtAndComputedDisagree_summarySaysSo() throws Exception {
        GetInfobaseSyncStateTool tool = tool();
        when(probe.read(conf, ProjectSyncState.CONFIGURATION, ib))
            .thenReturn(new ProjectSyncState("Demo", "configuration", "NOT_EQUAL", true, true, null));

        Map<String, Object> out = tool.call(Map.of("project", "Demo"));

        assertEquals(Boolean.FALSE, out.get("launchDialogExpected"));
        assertTrue(String.valueOf(out.get("summary")).contains("расходятся"));
    }

    @Test
    public void maxFilesOutOfRange_refused() throws Exception {
        GetInfobaseSyncStateTool tool = tool();
        try {
            tool.call(Map.of("project", "Demo", "maxFiles", 0));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("maxFiles"));
        }
    }
}
