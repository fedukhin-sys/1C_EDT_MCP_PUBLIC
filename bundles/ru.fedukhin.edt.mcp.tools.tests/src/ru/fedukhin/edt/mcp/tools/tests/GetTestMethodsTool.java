package ru.fedukhin.edt.mcp.tools.tests;

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
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.tests.internal.TestModuleHeuristic;

/**
 * {@code get_test_methods} — возвращает список тестовых методов в модуле.
 *
 * <p>Args: {@code { project, moduleFqn }}
 * <p>Result: {@code { moduleFqn, language, methods: [{name, fqn, registered}] }}
 */
public final class GetTestMethodsTool implements IMcpTool {

    private static final Pattern RU_TEST_PROC = Pattern.compile(
            "(?im)^\\s*Процедура\\s+(Тест_[\\w\\p{L}]+)\\s*\\(");
    private static final Pattern EN_TEST_PROC = Pattern.compile(
            "(?im)^\\s*Procedure\\s+(Test_[\\w\\p{L}]+)\\s*\\(");
    private static final Pattern RU_REGISTERED = Pattern.compile(
            "(?im)ЮнитТесты\\.ДобавитьТест\\(\"(Тест_[\\w\\p{L}]+)\"\\)");
    private static final Pattern EN_REGISTERED = Pattern.compile(
            "(?im)UnitTests\\.AddTest\\(\"(Test_[\\w\\p{L}]+)\"\\)");

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final TestModuleHeuristic heuristic;

    @Inject
    public GetTestMethodsTool(TestModuleHeuristic heuristic) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), heuristic);
    }

    /** Test seam. */
    public GetTestMethodsTool(Supplier<IWorkspaceRoot> rootSupplier,
                              TestModuleHeuristic heuristic) {
        this.rootSupplier = rootSupplier;
        this.heuristic    = heuristic;
    }

    @Override public String name()        { return "get_test_methods"; }
    @Override public String description() {
        return "Get list of xUnitFor1C test methods in a CommonModule";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project",   Map.of("type", "string"));
        properties.put("moduleFqn", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "moduleFqn"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String moduleFqn   = requireString(args, "moduleFqn");

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        String moduleName = extractModuleName(moduleFqn);
        String text = readModuleText(project, moduleName);

        String lang = heuristic.detectLanguage(moduleName, text);
        if (lang == null) lang = "ru"; // default

        // Collect registered method names
        List<String> registered = new ArrayList<>();
        Pattern regPat = "ru".equals(lang) ? RU_REGISTERED : EN_REGISTERED;
        Matcher rm = regPat.matcher(text);
        while (rm.find()) registered.add(rm.group(1));

        // Collect all test procedures
        Pattern procPat = "ru".equals(lang) ? RU_TEST_PROC : EN_TEST_PROC;
        List<Map<String, Object>> methods = new ArrayList<>();
        Matcher pm = procPat.matcher(text);
        while (pm.find()) {
            String fqn = pm.group(1); // e.g. "Тест_СозданиеСправочника"
            String prefix = "ru".equals(lang) ? "Тест_" : "Test_";
            String name = fqn.startsWith(prefix) ? fqn.substring(prefix.length()) : fqn;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("fqn", fqn);
            m.put("registered", registered.contains(fqn));
            methods.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moduleFqn", moduleFqn);
        out.put("language", lang);
        out.put("methods", methods);
        return out;
    }

    private static String extractModuleName(String fqn) throws ToolException {
        // "CommonModule.MyModule" -> "MyModule"
        int dot = fqn.indexOf('.');
        if (dot < 0 || dot == fqn.length() - 1) {
            throw new ToolException("invalid module FQN: '" + fqn + "'");
        }
        return fqn.substring(dot + 1);
    }

    private String readModuleText(IProject project, String moduleName) throws ToolException {
        IFile file = project.getFile("src/CommonModules/" + moduleName + "/Module.bsl");
        if (!file.exists()) {
            throw new ToolException("module file not found for '" + moduleName + "'");
        }
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
            throw new ToolException("failed to read module: " + e.getMessage());
        }
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
