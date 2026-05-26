package ru.fedukhin.edt.mcp.tools.bsl;

import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;

public class ReadModuleTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;

    @Inject
    public ReadModuleTool() {
        this(() -> ResourcesPlugin.getWorkspace().getRoot());
    }

    public ReadModuleTool(Supplier<IWorkspaceRoot> rootSupplier) {
        this.rootSupplier = rootSupplier;
    }

    @Override public String name() { return "read_module"; }
    @Override public String description() { return "Read full text of a BSL module"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> projectProp = new LinkedHashMap<>(); projectProp.put("type", "string");
        Map<String, Object> pathProp = new LinkedHashMap<>(); pathProp.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", projectProp);
        properties.put("path", pathProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "path"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = stringArg(args, "project");
        String path = stringArg(args, "path");
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

        String charsetName;
        try {
            charsetName = file.getCharset();
        } catch (CoreException e) {
            throw new ToolException("failed to detect charset: " + e.getMessage());
        }
        Charset charset;
        try {
            charset = Charset.forName(charsetName);
        } catch (RuntimeException e) {
            throw new ToolException("unsupported charset: " + charsetName);
        }

        StringBuilder sb = new StringBuilder();
        int newlineCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), charset))) {
            int c;
            while ((c = reader.read()) != -1) {
                sb.append((char) c);
                if (c == '\n') newlineCount++;
            }
        } catch (IOException | CoreException e) {
            throw new ToolException("failed to read: " + e.getMessage());
        }
        int lineCount = sb.length() == 0 ? 0 : newlineCount + 1;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projectName);
        out.put("path", path);
        out.put("charset", charsetName);
        out.put("content", sb.toString());
        out.put("lineCount", lineCount);
        return out;
    }

    static String stringArg(Map<String, Object> args, String key) throws ToolException {
        Object v = (args == null) ? null : args.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new ToolException("missing or empty '" + key + "' argument");
        }
        return (String) v;
    }
}
