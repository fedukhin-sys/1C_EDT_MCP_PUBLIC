package ru.fedukhin.edt.mcp.tools.quality;

import jakarta.inject.Inject;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckMarker;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckRunResult;
import ru.fedukhin.edt.mcp.tools.quality.internal.MarkerReader;
import ru.fedukhin.edt.mcp.tools.quality.internal.QualityArgs;
import ru.fedukhin.edt.mcp.tools.quality.internal.QualityJson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** {@code check_list_markers} — list current markers without running anything. */
public class CheckListMarkersTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final MarkerReader             reader;

    @Inject public CheckListMarkersTool(MarkerReader reader) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), reader);
    }

    /** Test seam. */
    public CheckListMarkersTool(Supplier<IWorkspaceRoot> rootSupplier, MarkerReader reader) {
        this.rootSupplier = rootSupplier;
        this.reader       = reader;
    }

    @Override public String name() { return "check_list_markers"; }

    @Override public String description() {
        return "List existing check markers without re-running validation; optional filters: "
             + "path (project-relative prefix, e.g. src/CommonModules/Foo/Module.bsl) / "
             + "severity / checkId / source";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = new LinkedHashMap<>(); str.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project",  new LinkedHashMap<>(str));
        properties.put("path",     new LinkedHashMap<>(str));
        properties.put("severity", new LinkedHashMap<>(str));
        properties.put("checkId",  new LinkedHashMap<>(str));
        properties.put("source",   new LinkedHashMap<>(str));
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
        String path     = QualityArgs.optStringArg(args, "path");
        String severity = QualityArgs.optStringArg(args, "severity");
        String checkId  = QualityArgs.optStringArg(args, "checkId");
        String source   = QualityArgs.optStringArg(args, "source");
        Set<String> ids = checkId == null ? null : Set.of(checkId);

        List<CheckMarker> markers = reader.read(project, path, severity, ids, source);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("markers", QualityJson.markersToList(markers));
        out.put("summary", QualityJson.severitySummaryToMap(CheckRunResult.SeveritySummary.of(markers)));
        return out;
    }
}
