package ru.fedukhin.edt.mcp.tools.tests;

import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
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
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.tests.internal.BslTestMethodAppender;
import ru.fedukhin.edt.mcp.tools.tests.internal.BslTestMethodAppender.AppendResult;
import ru.fedukhin.edt.mcp.tools.tests.internal.TestModuleHeuristic;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates.Language;

/**
 * {@code add_test_method} — добавляет тестовый метод в существующий xUnitFor1C модуль.
 *
 * <p>Args: {@code { project, moduleFqn, methodName, body? }}
 * <p>Result: {@code { moduleFqn, methodName, fqn, registered, alreadyExisted }}
 *
 * <p>Идемпотентен: если метод уже существует, возвращает {@code alreadyExisted=true} без изменений.
 */
public final class AddTestMethodTool implements IMcpTool {

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final TestModuleHeuristic      heuristic;
    private final BslTestMethodAppender    appender;

    @Inject
    public AddTestMethodTool(TestModuleHeuristic heuristic, BslTestMethodAppender appender) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), heuristic, appender);
    }

    /** Test seam. */
    public AddTestMethodTool(Supplier<IWorkspaceRoot> rootSupplier,
                             TestModuleHeuristic heuristic,
                             BslTestMethodAppender appender) {
        this.rootSupplier = rootSupplier;
        this.heuristic    = heuristic;
        this.appender     = appender;
    }

    @Override public String name()        { return "add_test_method"; }
    @Override public String description() {
        return "Add a xUnitFor1C test method to an existing test CommonModule";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project",    Map.of("type", "string"));
        properties.put("moduleFqn",  Map.of("type", "string"));
        properties.put("methodName", Map.of("type", "string"));
        properties.put("body",       Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "moduleFqn", "methodName"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String moduleFqn   = requireString(args, "moduleFqn");
        String methodName  = requireString(args, "methodName");
        String body        = args.get("body") instanceof String s ? s : null;

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        String moduleName = extractModuleName(moduleFqn);
        IFile bslFile = project.getFile("src/CommonModules/" + moduleName + "/Module.bsl");
        if (!bslFile.exists()) {
            throw new ToolException("module file not found for '" + moduleFqn + "'");
        }

        String text = readText(bslFile);
        String lang = heuristic.detectLanguage(moduleName, text);
        if (lang == null) lang = "ru";
        Language language = "en".equals(lang) ? Language.EN : Language.RU;

        AppendResult result = appender.append(text, methodName, language, body);

        if (!result.alreadyExisted) {
            writeText(bslFile, result.newText);
        }

        String prefix = XUnitTemplates.prefix(language);
        String fqn = methodName.startsWith(prefix) ? methodName : prefix + methodName;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moduleFqn",    moduleFqn);
        out.put("methodName",   methodName);
        out.put("fqn",          fqn);
        out.put("registered",   true);
        out.put("alreadyExisted", result.alreadyExisted);
        return out;
    }

    private static String extractModuleName(String fqn) throws ToolException {
        int dot = fqn.indexOf('.');
        if (dot < 0 || dot == fqn.length() - 1) {
            throw new ToolException("invalid module FQN: '" + fqn + "'");
        }
        return fqn.substring(dot + 1);
    }

    private String readText(IFile file) throws ToolException {
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

    private static void writeText(IFile file, String text) throws ToolException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (file.exists()) {
                file.setContents(new ByteArrayInputStream(bytes), true, true, new NullProgressMonitor());
            } else {
                file.create(new ByteArrayInputStream(bytes), false, new NullProgressMonitor());
            }
        } catch (CoreException e) {
            throw new ToolException("failed to write module: " + e.getMessage());
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
