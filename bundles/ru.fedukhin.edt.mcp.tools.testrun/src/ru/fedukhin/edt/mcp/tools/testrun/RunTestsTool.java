package ru.fedukhin.edt.mcp.tools.testrun;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.CreateInfobaseTool;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestResult;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunResult;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestRunnerLauncher;
import ru.fedukhin.edt.mcp.tools.testrun.internal.TestSelectorEncoder;

public class RunTestsTool implements IMcpTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 3600;

    private final TestRunnerLauncher launcher;
    private final InfobaseRegistry infobaseRegistry;
    private final RuntimeCli runtimeCli;

    @Inject
    public RunTestsTool(TestRunnerLauncher launcher, InfobaseRegistry infobaseRegistry,
                        RuntimeCli runtimeCli) {
        this.launcher = launcher;
        this.infobaseRegistry = infobaseRegistry;
        this.runtimeCli = runtimeCli;
    }

    @Override public String name() { return "run_tests"; }
    @Override public String description() {
        return "Run xUnit tests in module (or all modules) against a deployed infobase. "
             + "Pass user/password if the infobase has users; without them 1cv8 relies on OS "
             + "authentication and otherwise blocks on a login dialog until the timeout.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> proj = new LinkedHashMap<>(); proj.put("type", "string");
        Map<String, Object> ib = new LinkedHashMap<>(); ib.put("type", "string");
        Map<String, Object> mod = new LinkedHashMap<>(); mod.put("type", "string");
        Map<String, Object> user = new LinkedHashMap<>(); user.put("type", "string");
        Map<String, Object> password = new LinkedHashMap<>(); password.put("type", "string");
        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer"); timeout.put("minimum", MIN_TIMEOUT_SECONDS); timeout.put("maximum", MAX_TIMEOUT_SECONDS);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", proj);
        properties.put("infobase", ib);
        properties.put("moduleFqn", mod);
        properties.put("user", user);
        properties.put("password", password);
        properties.put("timeoutSeconds", timeout);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("project", "infobase"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String projectName = CreateInfobaseTool.stringArg(args, "project");
        String infobaseName = CreateInfobaseTool.stringArg(args, "infobase");
        String moduleFqn = (args != null && args.get("moduleFqn") instanceof String ms) ? ms : null;
        String user = (args != null && args.get("user") instanceof String us) ? us : null;
        String password = (args != null && args.get("password") instanceof String ps) ? ps : null;
        int timeoutSeconds = parseTimeout(args);

        IProject iproject = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!iproject.exists() || !iproject.isOpen()) {
            throw new ToolException("project '" + projectName + "' not open");
        }
        InfobaseReference ref = infobaseRegistry.findByName(infobaseName).orElseThrow(() ->
            new ToolException("infobase '" + infobaseName + "' not found"));
        String exe = runtimeCli.resolveExecutableForInfobase(ref);
        if (exe == null) {
            throw new ToolException("1cv8 executable not found for infobase '" + infobaseName + "'");
        }

        Path resultFile = TestRunnerLauncher.allocateResultFile();
        String selector = (moduleFqn == null)
            ? TestSelectorEncoder.encodeAll(resultFile.toString())
            : TestSelectorEncoder.encodeModule(moduleFqn, resultFile.toString());
        TestRunResult res = launcher.runWithTimeout(exe, ref.getConnectionString().asConnectionString(),
            selector, timeoutSeconds, user, password);
        return toMap(res);
    }

    private static int parseTimeout(Map<String, Object> args) throws ToolException {
        Object t = (args == null) ? null : args.get("timeoutSeconds");
        if (t == null) return DEFAULT_TIMEOUT_SECONDS;
        int v;
        if (t instanceof Number n) v = n.intValue();
        else throw new ToolException("timeoutSeconds must be an integer");
        if (v < MIN_TIMEOUT_SECONDS || v > MAX_TIMEOUT_SECONDS) {
            throw new ToolException("timeoutSeconds must be in ["
                + MIN_TIMEOUT_SECONDS + ", " + MAX_TIMEOUT_SECONDS + "], got " + v);
        }
        return v;
    }

    static Map<String, Object> toMap(TestRunResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", r.passed());
        out.put("failed", r.failed());
        out.put("durationMs", r.durationMs());
        List<Map<String, Object>> tests = new ArrayList<>();
        for (TestResult t : r.tests()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("module", t.module());
            m.put("name", t.name());
            m.put("status", t.status());
            m.put("durationMs", t.durationMs());
            if (t.message() != null) m.put("message", t.message());
            tests.add(m);
        }
        out.put("tests", tests);
        return out;
    }

    /** Assert-сообщения xUnit формируются в 1С и содержат реальные значения базы. */
    @Override public boolean returnsInfobaseData() { return true; }
}
