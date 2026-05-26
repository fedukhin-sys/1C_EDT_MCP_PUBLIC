package ru.fedukhin.edt.mcp.tools.tests;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.tests.internal.TestModuleHeuristic;

/**
 * {@code list_test_modules} — перечисляет тестовые CommonModule в проекте.
 *
 * <p>Args: {@code { project, language? }}
 * <p>Result: {@code { testModules: [{fqn, language, hasExecutableScenarios, methodCount}] }}
 */
public final class ListTestModulesTool implements IMcpTool {

    private static final Pattern RU_TEST_METHOD = Pattern.compile(
            "(?im)^\\s*Процедура\\s+Тест_[\\w\\p{L}]+\\s*\\(");
    private static final Pattern EN_TEST_METHOD = Pattern.compile(
            "(?im)^\\s*Procedure\\s+Test_[\\w\\p{L}]+\\s*\\(");

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IBmModelManager bm;
    private final IConfigurationProvider cfgProvider;
    private final TestModuleHeuristic heuristic;

    @Inject
    public ListTestModulesTool(IBmModelManager bm, IConfigurationProvider cfgProvider,
                               TestModuleHeuristic heuristic) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), bm, cfgProvider, heuristic);
    }

    /** Test seam. */
    public ListTestModulesTool(Supplier<IWorkspaceRoot> rootSupplier,
                               IBmModelManager bm,
                               IConfigurationProvider cfgProvider,
                               TestModuleHeuristic heuristic) {
        this.rootSupplier = rootSupplier;
        this.bm           = bm;
        this.cfgProvider  = cfgProvider;
        this.heuristic    = heuristic;
    }

    @Override public String name()        { return "list_test_modules"; }
    @Override public String description() {
        return "List xUnitFor1C test CommonModules in a project";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project",  Map.of("type", "string"));
        properties.put("language", Map.of("type", "string",
                "enum", List.of("ru", "en", "both")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String langFilter  = (args.get("language") instanceof String s) ? s : "both";

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        List<String> moduleNames = new ArrayList<>();
        Throwable[] err = new Throwable[1];

        bm.executeReadOnlyTask(project, (IBmSingleNamespaceTask<Void>) txn -> {
            try {
                Configuration cfg = resolveConfiguration(txn, project);
                if (cfg != null) {
                    @SuppressWarnings("unchecked")
                    Iterable<EObject> modules = (Iterable<EObject>)
                            cfg.getClass().getMethod("getCommonModules").invoke(cfg);
                    if (modules != null) {
                        for (EObject m : modules) {
                            Object name = m.getClass().getMethod("getName").invoke(m);
                            if (name instanceof String) moduleNames.add((String) name);
                        }
                    }
                }
            } catch (ReflectiveOperationException e) {
                err[0] = new ToolException("failed to enumerate CommonModules: " + e.getMessage());
            }
            return null;
        });

        if (err[0] instanceof ToolException te) throw te;

        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : moduleNames) {
            String text = readModuleText(project, name);
            String lang = heuristic.detectLanguage(name, text);
            if (lang == null) continue;
            if (!"both".equals(langFilter) && !lang.equals(langFilter)) continue;

            int count = countTestMethods(text, lang);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fqn", "CommonModule." + name);
            entry.put("language", lang);
            entry.put("hasExecutableScenarios", heuristic.hasExecutableScenarios(text));
            entry.put("methodCount", count);
            result.add(entry);
        }

        return Map.of("testModules", result);
    }

    private Configuration resolveConfiguration(com._1c.g5.v8.bm.core.IBmTransaction txn,
                                                IProject project) {
        IBmObject byLiteral = txn.getTopObjectByFqn("Configuration");
        if (byLiteral instanceof Configuration) return (Configuration) byLiteral;
        Configuration global = cfgProvider.getConfiguration(project);
        if (global != null) {
            EObject viaProvider = txn.toTransactionObject(global);
            if (viaProvider instanceof Configuration) return (Configuration) viaProvider;
        }
        return null;
    }

    private String readModuleText(IProject project, String moduleName) {
        IFile file = project.getFile("src/CommonModules/" + moduleName + "/Module.bsl");
        if (!file.exists()) return "";
        try {
            String charset = file.getCharset();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getContents(), Charset.forName(charset)))) {
                int c;
                while ((c = reader.read()) != -1) sb.append((char) c);
            }
            return sb.toString();
        } catch (CoreException | IOException e) {
            return "";
        }
    }

    private static int countTestMethods(String text, String lang) {
        if (text == null || text.isEmpty()) return 0;
        Pattern p = "ru".equals(lang) ? RU_TEST_METHOD : EN_TEST_METHOD;
        Matcher m = p.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
