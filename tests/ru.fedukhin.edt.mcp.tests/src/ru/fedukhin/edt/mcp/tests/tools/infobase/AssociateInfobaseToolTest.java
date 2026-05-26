package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.AssociateInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;

public class AssociateInfobaseToolTest {

    @Test
    public void call_happy_associatesAndSetsDefault() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("MyConf")).thenReturn(project);
        InfobaseReference ref = mock(InfobaseReference.class);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("Demo")).thenReturn(Optional.of(ref));
        IInfobaseAssociationManager assoc = mock(IInfobaseAssociationManager.class);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "MyConf"); args.put("infobase", "Demo");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) new AssociateInfobaseTool(() -> root, registry, assoc).call(args);
        assertEquals("MyConf", result.get("project"));
        assertEquals("Demo", result.get("infobase"));
        assertTrue((Boolean) result.get("default"));
        verify(assoc).associate(eq(project), eq(ref), any(InfobaseAssociationSettings.class));
        verify(assoc).setDefaultInfobase(eq(project), eq(ref), any(InfobaseAssociationContext.class));
    }

    @Test
    public void call_setDefaultFalse_doesNotCallSetDefault() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("MyConf")).thenReturn(project);
        InfobaseReference ref = mock(InfobaseReference.class);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("Demo")).thenReturn(Optional.of(ref));
        IInfobaseAssociationManager assoc = mock(IInfobaseAssociationManager.class);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "MyConf"); args.put("infobase", "Demo"); args.put("setDefault", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) new AssociateInfobaseTool(() -> root, registry, assoc).call(args);
        assertFalse((Boolean) result.get("default"));
        verify(assoc, never()).setDefaultInfobase(any(), any(), any());
    }

    @Test
    public void call_projectNotOpen_throws() {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(false);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("X")).thenReturn(project);

        Map<String, Object> args = new HashMap<>();
        args.put("project", "X"); args.put("infobase", "Demo");
        try {
            new AssociateInfobaseTool(() -> root, mock(InfobaseRegistry.class),
                                      mock(IInfobaseAssociationManager.class)).call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_infobaseNotFound_throws() {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("X")).thenReturn(project);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("Missing")).thenReturn(Optional.empty());

        Map<String, Object> args = new HashMap<>();
        args.put("project", "X"); args.put("infobase", "Missing");
        try {
            new AssociateInfobaseTool(() -> root, registry, mock(IInfobaseAssociationManager.class)).call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }

    @Test
    public void call_associateThrows_propagated() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true); when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("X")).thenReturn(project);
        InfobaseReference ref = mock(InfobaseReference.class);
        InfobaseRegistry registry = mock(InfobaseRegistry.class);
        when(registry.findByName("Demo")).thenReturn(Optional.of(ref));
        IInfobaseAssociationManager assoc = mock(IInfobaseAssociationManager.class);
        org.mockito.Mockito.doThrow(new InfobaseAssociationException("nope"))
            .when(assoc).associate(eq(project), eq(ref), any(InfobaseAssociationSettings.class));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "X"); args.put("infobase", "Demo");
        try {
            new AssociateInfobaseTool(() -> root, registry, assoc).call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }
}
