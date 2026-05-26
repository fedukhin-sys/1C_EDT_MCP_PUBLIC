package ru.fedukhin.edt.mcp.tools.bsl;

import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader.MethodInfo;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslParseException;

public class GetMethodTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final BslAstReader reader;

    @Inject
    public GetMethodTool(BslAstReader reader) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), reader);
    }

    public GetMethodTool(Supplier<IWorkspaceRoot> rootSupplier, BslAstReader reader) {
        this.rootSupplier = rootSupplier;
        this.reader = reader;
    }

    @Override public String name() { return "get_method"; }
    @Override public String description() { return "Get text and metadata of a specific procedure or function in a BSL module"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> projectProp = new LinkedHashMap<>(); projectProp.put("type", "string");
        Map<String, Object> pathProp = new LinkedHashMap<>(); pathProp.put("type", "string");
        Map<String, Object> nameProp = new LinkedHashMap<>(); nameProp.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", projectProp);
        properties.put("path", pathProp);
        properties.put("name", nameProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "path", "name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = ReadModuleTool.stringArg(args, "project");
        String path = ReadModuleTool.stringArg(args, "path");
        String methodName = ReadModuleTool.stringArg(args, "name");
        if (!path.endsWith(".bsl")) {
            throw new ToolException("not a BSL module: " + path);
        }
        IProject project = rootSupplier.get().getProject(projectName);
        if (!project.exists()) {
            throw new ToolException("project '" + projectName + "' not found");
        }
        IFile file = project.getFile(path);
        if (!file.exists()) {
            throw new ToolException("module '" + path + "' not found in project '" + projectName + "'");
        }

        Optional<MethodInfo> found;
        try {
            found = reader.findMethod(file, methodName);
        } catch (BslParseException e) {
            throw new ToolException("failed to parse BSL: " + e.getMessage());
        }
        if (found.isEmpty()) {
            throw new ToolException("method '" + methodName + "' not found in '" + path + "'");
        }
        MethodInfo info = found.get();
        String text = readSubstring(file, info.offset(), info.length());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", info.name());
        out.put("kind", info.kind());
        out.put("export", info.export());
        out.put("lineStart", info.lineStart());
        out.put("lineEnd", info.lineEnd());
        out.put("text", text);
        return out;
    }

    private static String readSubstring(IFile file, int offset, int length) throws ToolException {
        if (offset < 0 || length < 0) {
            throw new ToolException("method node has no position info");
        }
        String charsetName;
        try {
            charsetName = file.getCharset();
        } catch (CoreException e) {
            throw new ToolException("failed to detect charset: " + e.getMessage());
        }
        Charset charset = Charset.forName(charsetName);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), charset))) {
            long skipped = reader.skip(offset);
            if (skipped < offset) {
                throw new ToolException("offset " + offset + " beyond end of file");
            }
            char[] buf = new char[length];
            int read = 0;
            while (read < length) {
                int n = reader.read(buf, read, length - read);
                if (n < 0) break;
                read += n;
            }
            sb.append(buf, 0, read);
        } catch (IOException | CoreException e) {
            throw new ToolException("failed to read substring: " + e.getMessage());
        }
        return sb.toString();
    }
}
