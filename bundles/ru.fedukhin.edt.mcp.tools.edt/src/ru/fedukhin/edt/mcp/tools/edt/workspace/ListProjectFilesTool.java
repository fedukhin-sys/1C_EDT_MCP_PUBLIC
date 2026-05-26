package ru.fedukhin.edt.mcp.tools.edt.workspace;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;

public class ListProjectFilesTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;

    @Inject
    public ListProjectFilesTool() {
        this(() -> ResourcesPlugin.getWorkspace().getRoot());
    }

    public ListProjectFilesTool(Supplier<IWorkspaceRoot> rootSupplier) {
        this.rootSupplier = rootSupplier;
    }

    @Override public String name() { return "list_project_files"; }
    @Override public String description() { return "List files and folders in a project (paths only, no content)"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        Map<String, Object> globProp = new LinkedHashMap<>();
        globProp.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        properties.put("glob", globProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public List<Map<String, Object>> call(Map<String, Object> args) throws ToolException {
        String name = GetProjectTool.stringArg(args, "name");
        String glob = (args == null) ? null : (String) args.get("glob");
        Pattern pattern = (glob == null || glob.isEmpty()) ? null : compileGlob(glob);

        IProject project = rootSupplier.get().getProject(name);
        if (!project.exists()) {
            throw new ToolException("project '" + name + "' not found");
        }
        if (!project.isOpen()) {
            throw new ToolException("project '" + name + "' is closed; open it first");
        }

        List<Map<String, Object>> out = new ArrayList<>();
        try {
            collect(project, project, pattern, out);
        } catch (CoreException e) {
            throw new ToolException("failed to read project members: " + e.getMessage());
        }
        out.sort((a, b) -> ((String) a.get("path")).compareTo((String) b.get("path")));
        return out;
    }

    private static void collect(IProject project, IContainer container, Pattern pattern,
            List<Map<String, Object>> out) throws CoreException {
        for (IResource r : container.members()) {
            String rel = r.getFullPath().makeRelativeTo(project.getFullPath()).toString();
            boolean isFolder = r.getType() == IResource.FOLDER;
            if (pattern == null || pattern.matcher(rel).matches()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", rel);
                entry.put("type", isFolder ? "folder" : "file");
                out.add(entry);
            }
            if (isFolder) {
                collect(project, (IContainer) r, pattern, out);
            }
        }
    }

    // Minimal glob: '*' -> '.*', '?' -> '.', everything else literal-escaped.
    // Full glob isn't needed for Stage 1; Stage 2+ may upgrade for BSL filtering.
    private static Pattern compileGlob(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString());
    }
}
