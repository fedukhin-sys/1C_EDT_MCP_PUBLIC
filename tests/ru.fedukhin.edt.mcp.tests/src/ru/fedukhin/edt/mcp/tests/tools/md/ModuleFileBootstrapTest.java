package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.internal.ModuleFileBootstrap;

public class ModuleFileBootstrapTest {

    @Test
    public void ensureCreatesFileAtExpectedPath() throws Exception {
        IProject project = mock(IProject.class);
        IFolder commonModules = mock(IFolder.class);
        IFolder moduleFolder  = mock(IFolder.class);
        IFile   moduleFile    = mock(IFile.class);

        when(project.getName()).thenReturn("Demo");
        when(project.getFolder("src")).thenReturn(commonModules);
        when(commonModules.getFolder("CommonModules")).thenReturn(commonModules);
        when(commonModules.getFolder("MCPTest_Mod")).thenReturn(moduleFolder);
        when(moduleFolder.getFile("Module.bsl")).thenReturn(moduleFile);
        when(commonModules.exists()).thenReturn(true);
        when(moduleFolder.exists()).thenReturn(false);
        when(moduleFile.exists()).thenReturn(false);

        ModuleFileBootstrap b = new ModuleFileBootstrap();
        String path = b.ensureModuleBsl(project, "CommonModule.MCPTest_Mod");

        assertEquals("src/CommonModules/MCPTest_Mod/Module.bsl", path);
        verify(moduleFolder).create(eq(false), eq(true), any());
        verify(moduleFile).create(any(), eq(false), any());
    }

    @Test
    public void ensureIsIdempotentWhenFileExists() throws Exception {
        IProject project = mock(IProject.class);
        IFolder commonModules = mock(IFolder.class);
        IFolder moduleFolder  = mock(IFolder.class);
        IFile   moduleFile    = mock(IFile.class);
        when(project.getFolder("src")).thenReturn(commonModules);
        when(commonModules.getFolder("CommonModules")).thenReturn(commonModules);
        when(commonModules.getFolder("MCPTest_Mod")).thenReturn(moduleFolder);
        when(moduleFolder.getFile("Module.bsl")).thenReturn(moduleFile);
        when(commonModules.exists()).thenReturn(true);
        when(moduleFolder.exists()).thenReturn(true);
        when(moduleFile.exists()).thenReturn(true);

        ModuleFileBootstrap b = new ModuleFileBootstrap();
        String path = b.ensureModuleBsl(project, "CommonModule.MCPTest_Mod");

        assertEquals("src/CommonModules/MCPTest_Mod/Module.bsl", path);
        verify(moduleFolder, never()).create(anyBoolean(), anyBoolean(), any());
        verify(moduleFile, never()).create(any(), anyBoolean(), any());
    }
}
