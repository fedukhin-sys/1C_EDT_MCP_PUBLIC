package ru.fedukhin.edt.mcp.tools.quality;

import jakarta.inject.Inject;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckRunResult;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckRunner;
import ru.fedukhin.edt.mcp.tools.quality.internal.QualityArgs;
import ru.fedukhin.edt.mcp.tools.quality.internal.QualityJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** {@code check_run} — run validation on a project (or file path) and return markers. */
public class CheckRunTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final CheckRunner              runner;

    @Inject
    public CheckRunTool(CheckRunner runner) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), runner);
    }

    /** Test seam. */
    public CheckRunTool(Supplier<IWorkspaceRoot> rootSupplier, CheckRunner runner) {
        this.rootSupplier = rootSupplier;
        this.runner       = runner;
    }

    @Override public String name() { return "check_run"; }

    @Override public String description() {
        return "Run validation on a project (or a file path within); blocks until completion "
             + "or waitSeconds expires";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> integer = new LinkedHashMap<>(); integer.put("type", "integer");
        Map<String, Object> bool = new LinkedHashMap<>(); bool.put("type", "boolean");
        Map<String, Object> strArr = new LinkedHashMap<>();
        strArr.put("type", "array");
        Map<String, Object> strItem = new LinkedHashMap<>(); strItem.put("type", "string");
        strArr.put("items", strItem);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project",      new LinkedHashMap<>(str));
        properties.put("path",         new LinkedHashMap<>(str));
        properties.put("checkIds",     new LinkedHashMap<>(strArr));
        properties.put("waitSeconds",  new LinkedHashMap<>(integer));
        properties.put("clearFirst",   new LinkedHashMap<>(bool));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Object call(Map<String, Object> args) throws ToolException {
        String name = QualityArgs.stringArg(args, "project");
        IProject project = rootSupplier.get().getProject(name);
        if (!project.exists()) {
            throw new ToolException("project '" + name + "' not found");
        }
        String path = QualityArgs.optStringArg(args, "path");
        List<String> idList = QualityArgs.stringListArg(args, "checkIds");
        Set<String> ids = idList == null ? Set.of() : new LinkedHashSet<>(idList);
        int waitSeconds = QualityArgs.intArg(args, "waitSeconds", 60, 1, 600);
        boolean clearFirst = QualityArgs.boolArg(args, "clearFirst", true);

        List<Object> scope;
        String scopeLabel;
        if (path == null) {
            scope = Collections.emptyList();
            scopeLabel = "project";
        } else {
            scope = new ArrayList<>();
            scope.add("/" + name + "/" + path);
            scopeLabel = "file";
        }
        CheckRunResult result = runner.run(project, scope, path, ids, waitSeconds, clearFirst);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ran",           true);
        out.put("project",       name);
        out.put("scope",         scopeLabel);
        out.put("waitedSeconds", result.waitedSeconds());
        out.put("completed",     result.completed());
        out.put("markers",       QualityJson.markersToList(result.markers()));
        out.put("summary",       QualityJson.severitySummaryToMap(result.summary()));
        return out;
    }
}
