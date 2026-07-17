package ru.fedukhin.edt.mcp.tests.tools.bsl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.bsl.WriteModuleTool;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader;

public class WriteModuleToolTest {

    private static IWorkspace runImmediately() throws Exception {
        IWorkspace ws = mock(IWorkspace.class);
        doAnswer(inv -> {
            ((IWorkspaceRunnable) inv.getArgument(0)).run(mock(IProgressMonitor.class));
            return null;
        }).when(ws).run(any(IWorkspaceRunnable.class), any(IProgressMonitor.class));
        return ws;
    }

    private static IFile bslFile(String charset) throws Exception {
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(true);
        when(file.getCharset()).thenReturn(charset);
        when(file.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path("/P/src/M.bsl"));
        return file;
    }

    /**
     * read_module снимает BOM с content'а — если write_module его не вернёт, круг
     * read→edit→write молча лишит модуль BOM, которым 1С предваряет .bsl.
     */
    @Test
    public void call_existingFileHadBom_bomIsPreserved() throws Exception {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] old = ("﻿Процедура Old()\nКонецПроцедуры\n").getBytes(StandardCharsets.UTF_8);

        IFile file = bslFile("UTF-8");
        when(file.getContents()).thenAnswer(inv -> new java.io.ByteArrayInputStream(old));
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/M.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        byte[][] written = new byte[1][];
        doAnswer(inv -> {
            written[0] = ((java.io.InputStream) inv.getArgument(0)).readAllBytes();
            return null;
        }).when(file).setContents(any(java.io.InputStream.class), anyBoolean(), anyBoolean(),
                any(IProgressMonitor.class));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/M.bsl");
        args.put("content", "Процедура F()\nКонецПроцедуры\n");
        args.put("validate", Boolean.FALSE);
        new WriteModuleTool(runImmediately(), () -> root, mock(BslAstReader.class)).call(args);

        assertNotNull("setContents должен был получить байты", written[0]);
        assertArrayEquals("BOM исходного файла обязан сохраниться",
                bom, java.util.Arrays.copyOf(written[0], 3));
    }

    @Test
    public void call_existingFileWithoutBom_bomIsNotAdded() throws Exception {
        byte[] old = "Процедура Old()\nКонецПроцедуры\n".getBytes(StandardCharsets.UTF_8);

        IFile file = bslFile("UTF-8");
        when(file.getContents()).thenAnswer(inv -> new java.io.ByteArrayInputStream(old));
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/M.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        byte[][] written = new byte[1][];
        doAnswer(inv -> {
            written[0] = ((java.io.InputStream) inv.getArgument(0)).readAllBytes();
            return null;
        }).when(file).setContents(any(java.io.InputStream.class), anyBoolean(), anyBoolean(),
                any(IProgressMonitor.class));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/M.bsl");
        args.put("content", "Процедура F()\nКонецПроцедуры\n");
        args.put("validate", Boolean.FALSE);
        new WriteModuleTool(runImmediately(), () -> root, mock(BslAstReader.class)).call(args);

        assertNotNull(written[0]);
        assertEquals("файл без BOM не должен его получить",
                'П', new String(written[0], StandardCharsets.UTF_8).charAt(0));
    }

    @Test
    public void call_writesContent_whenValidateFalse() throws Exception {
        IFile file = bslFile("UTF-8");
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/M.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        IWorkspace ws = runImmediately();
        BslAstReader reader = mock(BslAstReader.class);
        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/M.bsl");
        args.put("content", "Процедура F()\nКонецПроцедуры\n");
        args.put("validate", Boolean.FALSE);

        Map<String, Object> result = new WriteModuleTool(ws, () -> root, reader).call(args);
        verify(file).setContents(any(java.io.InputStream.class), anyBoolean(), anyBoolean(), any(IProgressMonitor.class));
        assertEquals(Boolean.TRUE, result.get("written"));
        assertEquals(Boolean.FALSE, result.get("validated"));
        assertNotNull(result.get("bytes"));
    }

    @Test
    public void call_validatesAndWrites_whenValidatePasses() throws Exception {
        IFile file = bslFile("UTF-8");
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/M.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        IWorkspace ws = runImmediately();
        BslAstReader reader = mock(BslAstReader.class);
        when(reader.validate(any(String.class), any(URI.class))).thenReturn(Collections.emptyList());

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/M.bsl");
        args.put("content", "Процедура F() КонецПроцедуры\n");
        args.put("validate", Boolean.TRUE);

        Map<String, Object> result = new WriteModuleTool(ws, () -> root, reader).call(args);
        assertEquals(Boolean.TRUE, result.get("written"));
        assertEquals(Boolean.TRUE, result.get("validated"));
        @SuppressWarnings("unchecked")
        List<String> errs = (List<String>) result.get("syntaxErrors");
        assertEquals(0, errs.size());
    }

    @Test
    public void call_skipsWrite_whenValidateFails() throws Exception {
        IFile file = bslFile("UTF-8");
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/M.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        IWorkspace ws = runImmediately();
        BslAstReader reader = mock(BslAstReader.class);
        when(reader.validate(any(String.class), any(URI.class)))
            .thenReturn(List.of("1:1: syntax error"));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/M.bsl");
        args.put("content", "abracadabra\n");
        args.put("validate", Boolean.TRUE);

        Map<String, Object> result = new WriteModuleTool(ws, () -> root, reader).call(args);
        assertEquals(Boolean.FALSE, result.get("written"));
        assertEquals(Boolean.FALSE, result.get("validated"));
        @SuppressWarnings("unchecked")
        List<String> errs = (List<String>) result.get("syntaxErrors");
        assertEquals(1, errs.size());
        verify(file, org.mockito.Mockito.never()).setContents(any(java.io.InputStream.class), anyBoolean(), anyBoolean(), any(IProgressMonitor.class));
    }

    @Test
    public void call_createsFile_whenMissing() throws Exception {
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(false);
        when(file.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path("/P/src/X.bsl"));
        // parent is a plain IContainer (not IFolder) → createParentFolders is a no-op
        when(file.getParent()).thenReturn(mock(IContainer.class));
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.getFile("src/X.bsl")).thenReturn(file);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("P")).thenReturn(project);

        BslAstReader reader = mock(BslAstReader.class);
        when(reader.validate(any(String.class), any(URI.class))).thenReturn(Collections.emptyList());

        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/X.bsl");
        args.put("content", "Процедура F()\nКонецПроцедуры\n");

        Map<String, Object> result =
            new WriteModuleTool(runImmediately(), () -> root, reader).call(args);
        verify(file).create(any(java.io.InputStream.class), anyBoolean(), any(IProgressMonitor.class));
        assertEquals(Boolean.TRUE, result.get("written"));
        assertEquals(Boolean.TRUE, result.get("created"));
    }

    @Test
    public void call_notBslPathThrows() throws Exception {
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        Map<String, Object> args = new HashMap<>();
        args.put("project", "P"); args.put("path", "src/notes.txt"); args.put("content", "x");
        try {
            new WriteModuleTool(runImmediately(), () -> root, mock(BslAstReader.class)).call(args);
            fail("expected ToolException");
        } catch (ToolException e) { /* ok */ }
    }

    @Test
    public void metadata_isCorrect() throws Exception {
        WriteModuleTool tool = new WriteModuleTool(runImmediately(),
            () -> mock(IWorkspaceRoot.class), mock(BslAstReader.class));
        assertEquals("write_module", tool.name());
    }
}
