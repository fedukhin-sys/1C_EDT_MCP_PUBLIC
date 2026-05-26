package ru.fedukhin.edt.mcp.tools.bsl;

import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader;

public class WriteModuleTool implements IMcpTool {

    private final IWorkspace workspace;
    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final BslAstReader reader;

    @Inject
    public WriteModuleTool(BslAstReader reader) {
        this(ResourcesPlugin.getWorkspace(),
             () -> ResourcesPlugin.getWorkspace().getRoot(), reader);
    }

    public WriteModuleTool(IWorkspace workspace, Supplier<IWorkspaceRoot> rootSupplier, BslAstReader reader) {
        this.workspace = workspace;
        this.rootSupplier = rootSupplier;
        this.reader = reader;
    }

    @Override public String name() { return "write_module"; }
    @Override public String description() { return "Write BSL module content (optionally validated by Xtext before write)"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> projectProp = new LinkedHashMap<>(); projectProp.put("type", "string");
        Map<String, Object> pathProp = new LinkedHashMap<>(); pathProp.put("type", "string");
        Map<String, Object> contentProp = new LinkedHashMap<>(); contentProp.put("type", "string");
        Map<String, Object> validateProp = new LinkedHashMap<>(); validateProp.put("type", "boolean");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", projectProp);
        properties.put("path", pathProp);
        properties.put("content", contentProp);
        properties.put("validate", validateProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "path", "content"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = ReadModuleTool.stringArg(args, "project");
        String path = ReadModuleTool.stringArg(args, "path");
        String content = ReadModuleTool.stringArg(args, "content");
        boolean validate = !(args != null && Boolean.FALSE.equals(args.get("validate")));

        if (!path.endsWith(".bsl")) {
            throw new ToolException("not a BSL module: " + path);
        }
        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists()) {
            throw new ToolException("project '" + projectName + "' not found");
        }
        IFile file = project.getFile(path);
        boolean creating = !file.exists();

        Charset charset;
        if (creating) {
            // New BSL module: EDT projects store .bsl as UTF-8.
            charset = StandardCharsets.UTF_8;
        } else {
            String charsetName;
            try {
                charsetName = file.getCharset();
            } catch (CoreException e) {
                throw new ToolException("failed to detect charset: " + e.getMessage());
            }
            try {
                charset = Charset.forName(charsetName);
            } catch (RuntimeException e) {
                throw new ToolException("unsupported charset: " + charsetName);
            }
        }

        List<String> syntaxErrors = List.of();
        if (validate) {
            URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
            syntaxErrors = reader.validate(content, uri);
            if (!syntaxErrors.isEmpty()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("path", path);
                out.put("written", false);
                out.put("validated", false);
                out.put("syntaxErrors", syntaxErrors);
                return out;
            }
        }

        byte[] bytes = content.getBytes(charset);
        try {
            workspace.run((IWorkspaceRunnable) monitor -> {
                if (creating) {
                    createParentFolders(file, monitor);
                    file.create(new ByteArrayInputStream(bytes), true, monitor);
                } else {
                    file.setContents(new ByteArrayInputStream(bytes), true, true, monitor);
                }
            }, new NullProgressMonitor());
        } catch (CoreException e) {
            throw new ToolException("failed to write: " + e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("written", true);
        out.put("created", creating);
        out.put("validated", validate);
        out.put("bytes", bytes.length);
        out.put("syntaxErrors", syntaxErrors);
        return out;
    }

    /** Creates any missing parent folders of {@code file}, outermost-first. */
    private static void createParentFolders(IFile file, IProgressMonitor monitor) throws CoreException {
        Deque<IFolder> missing = new ArrayDeque<>();
        for (IContainer c = file.getParent(); c instanceof IFolder folder && !c.exists(); c = c.getParent()) {
            missing.push(folder);
        }
        for (IFolder folder : missing) {
            folder.create(true, true, monitor);
        }
    }
}
