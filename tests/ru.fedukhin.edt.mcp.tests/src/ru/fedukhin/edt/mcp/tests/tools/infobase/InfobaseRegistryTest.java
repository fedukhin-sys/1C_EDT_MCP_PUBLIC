package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferenceException;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;

public class InfobaseRegistryTest {

    @Test
    public void listAll_filtersOutNonInfobaseSections() {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        Section group = mock(Section.class);   // non-InfobaseReference section, e.g. a Group
        when(mgr.getAll()).thenReturn(Arrays.asList(ref, group));

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        List<InfobaseReference> result = r.listAll();
        assertEquals(1, result.size());
        assertEquals(ref, result.get(0));
    }

    @Test
    public void findByName_delegates() {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(mgr.findInfobaseByName("X")).thenReturn(Optional.of(ref));

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        assertEquals(Optional.of(ref), r.findByName("X"));
    }

    @Test
    public void delete_callsManagerAndDoesNotTouchFilesByDefault() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        FileConnectionString cs = mock(FileConnectionString.class);
        Path tmp = Files.createTempDirectory("ib-delete-default");
        when(cs.getFile()).thenReturn(tmp.toString());
        when(ref.getConnectionString()).thenReturn(cs);

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        r.delete(ref, false);

        verify(mgr).delete(ref);
        assertTrue("dir must remain when deleteFiles=false", Files.exists(tmp));
        Files.deleteIfExists(tmp);
    }

    @Test
    public void delete_deleteFilesTrue_removesDirectory() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        FileConnectionString cs = mock(FileConnectionString.class);
        Path tmp = Files.createTempDirectory("ib-delete-files");
        Files.createFile(tmp.resolve("1Cv8.1CD"));
        when(cs.getFile()).thenReturn(tmp.toString());
        when(ref.getConnectionString()).thenReturn(cs);

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        r.delete(ref, true);

        verify(mgr).delete(ref);
        assertFalse("dir must be removed", Files.exists(tmp));
    }

    @Test
    public void delete_managerThrows_propagatedAsToolException() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        org.mockito.Mockito.doThrow(new InfobaseReferenceException("in use")).when(mgr).delete(ref);

        InfobaseRegistry r = new InfobaseRegistry(mgr, mock(RuntimeCli.class));
        try {
            r.delete(ref, false);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("in use"));
        }
    }

    @Test
    public void createFileInfobase_locationNotEmpty_throwsBeforeProcess() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        RuntimeCli cli = mock(RuntimeCli.class);
        Path tmp = Files.createTempDirectory("ib-not-empty");
        Files.createFile(tmp.resolve("dummy"));

        InfobaseRegistry r = new InfobaseRegistry(mgr, cli);
        try {
            r.createFileInfobase("X", tmp, "8.3.24", "Demo", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("not empty"));
        }
        verify(cli, never()).createFileInfobase(any(), any(), any());
        Files.deleteIfExists(tmp.resolve("dummy"));
        Files.deleteIfExists(tmp);
    }

    @Test
    public void createFileInfobase_happy_callsCliAndAddsToManager() throws Exception {
        IInfobaseManager mgr = mock(IInfobaseManager.class);
        RuntimeCli cli = mock(RuntimeCli.class);
        Path tmp = Files.createTempDirectory("ib-happy-parent").resolve("Demo");
        // tmp does NOT yet exist — registry must allow this.

        InfobaseRegistry r = new InfobaseRegistry(mgr, cli);
        InfobaseReference ref = r.createFileInfobase("Demo", tmp, "8.3.24", "DemoGroup", Duration.ofSeconds(60));

        verify(cli).createFileInfobase(tmp, "8.3.24", Duration.ofSeconds(60));
        verify(mgr).add(ref, "DemoGroup");
        assertEquals("Demo", ref.getName());
        assertEquals(UUID.class, ref.getUuid().getClass());
    }
}
