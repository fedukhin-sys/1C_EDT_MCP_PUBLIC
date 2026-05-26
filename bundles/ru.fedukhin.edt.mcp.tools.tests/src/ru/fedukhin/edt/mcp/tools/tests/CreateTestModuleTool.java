package ru.fedukhin.edt.mcp.tools.tests;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.ModuleFileBootstrap;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates.Language;

/**
 * {@code create_test_module} — создаёт тестовый CommonModule (xUnitFor1C) в проекте.
 *
 * <p>Args: {@code { project, name, language? }}
 * <p>Result: {@code { fqn, modulePath, language }}
 *
 * <p>Логика:
 * 1. Создаёт CommonModule через {@link MdObjectFactory}.
 * 2. Устанавливает {@code Server=true} через отдельную BM read-write транзакцию.
 * 3. Создаёт Module.bsl через {@link ModuleFileBootstrap}.
 * 4. Записывает скелет xUnitFor1C через {@code IFile.setContents}.
 */
public final class CreateTestModuleTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final BmPersistentExecutor     executor;
    private final MdObjectFactory          factory;
    private final MdObjectLocator          locator;
    private final PropertyAccessor         accessor;
    private final ModuleFileBootstrap      bootstrap;

    @Inject
    public CreateTestModuleTool(BmPersistentExecutor executor, MdObjectFactory factory,
                                MdObjectLocator locator, PropertyAccessor accessor,
                                ModuleFileBootstrap bootstrap) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), executor, factory, locator, accessor, bootstrap);
    }

    /** Test seam. */
    public CreateTestModuleTool(Supplier<IWorkspaceRoot> rootSupplier,
                                BmPersistentExecutor executor, MdObjectFactory factory,
                                MdObjectLocator locator, PropertyAccessor accessor,
                                ModuleFileBootstrap bootstrap) {
        this.rootSupplier = rootSupplier;
        this.executor     = executor;
        this.factory      = factory;
        this.locator      = locator;
        this.accessor     = accessor;
        this.bootstrap    = bootstrap;
    }

    @Override public String name()        { return "create_test_module"; }
    @Override public String description() {
        return "Create a new xUnitFor1C test CommonModule (Server=true, with ExecutableScenarios skeleton)";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project",  Map.of("type", "string"));
        properties.put("name",     Map.of("type", "string"));
        properties.put("language", Map.of("type", "string", "enum", List.of("ru", "en")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String name        = requireString(args, "name");
        String langArg     = args.get("language") instanceof String s ? s : "ru";
        Language lang      = "en".equalsIgnoreCase(langArg) ? Language.EN : Language.RU;

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        // Step 1: create CommonModule via MdObjectFactory
        String fqn = factory.create(project, "CommonModule", name, null, null);

        // Step 2: set Server=true in a BM read-write task
        Throwable[] err = new Throwable[1];
        executor.execute(project, "MCP set Server=true on " + fqn,
                (IBmSingleNamespaceTask<Void>) txn -> {
            try {
                IBmObject bmObj = locator.findTop(txn, fqn, projectName);
                accessor.set((EObject) bmObj, "CommonModule", project, "server", true);
            } catch (ToolException e) {
                err[0] = e;
            }
            return null;
        });
        if (err[0] instanceof ToolException te) throw te;

        // Step 3: ensure Module.bsl exists
        String modulePath = bootstrap.ensureModuleBsl(project, fqn);

        // Step 4: write xUnitFor1C skeleton
        String skeleton = XUnitTemplates.moduleBody(lang);
        IFile bslFile = project.getFile(modulePath);
        try {
            byte[] bytes = skeleton.getBytes(StandardCharsets.UTF_8);
            if (bslFile.exists()) {
                bslFile.setContents(new ByteArrayInputStream(bytes), true, true, new NullProgressMonitor());
            } else {
                bslFile.create(new ByteArrayInputStream(bytes), false, new NullProgressMonitor());
            }
        } catch (CoreException e) {
            throw new ToolException("failed to write test module skeleton: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn",        fqn);
        result.put("modulePath", modulePath);
        result.put("language",   lang == Language.RU ? "ru" : "en");
        return result;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
