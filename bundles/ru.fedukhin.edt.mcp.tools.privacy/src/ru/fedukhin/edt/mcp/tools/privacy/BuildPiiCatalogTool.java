package ru.fedukhin.edt.mcp.tools.privacy;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.core.privacy.CatalogStore;
import ru.fedukhin.edt.mcp.core.privacy.PiiCatalog;
import ru.fedukhin.edt.mcp.tools.privacy.internal.MetadataScanner;

/**
 * {@code build_pii_catalog} — построить первоначальный каталог ПДн
 * (.mcp/pii-catalog.json) по модели метаданных проекта (fail-closed).
 */
public final class BuildPiiCatalogTool implements IMcpTool {

    private final IBmModelManager bm;
    private final IConfigurationProvider cfg;

    @Inject
    public BuildPiiCatalogTool(IBmModelManager bm, IConfigurationProvider cfg) {
        this.bm = bm;
        this.cfg = cfg;
    }

    @Override public String name() { return "build_pii_catalog"; }

    @Override public String description() {
        return "Build the initial PII catalog (.mcp/pii-catalog.json) from the project's metadata model (fail-closed)";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", str);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        Object projectArg = args.get("project");
        if (!(projectArg instanceof String projectName) || projectName.isEmpty()) {
            throw new ToolException("'project' must be a non-empty string");
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        PiiCatalog catalog = new MetadataScanner(bm, cfg).scan(project);
        try {
            new CatalogStore().write(projectName, catalog);
        } catch (java.io.IOException e) {
            throw new ToolException("cannot write catalog: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", CatalogStore.CATALOG_REL);
        out.put("objects", catalog.objects().size());
        out.put("attributes", catalog.attributes().size());
        return out;
    }
}
