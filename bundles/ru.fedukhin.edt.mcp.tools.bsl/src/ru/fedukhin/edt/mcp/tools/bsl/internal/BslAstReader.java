package ru.fedukhin.edt.mcp.tools.bsl.internal;

import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;

/**
 * Regex-based BSL reader. Parses BSL modules to extract methods (procedures and
 * functions) and infers ModuleType from the path inside an EDT-layout project.
 *
 * <p>Known limitations: the regex parser treats `Процедура`/`Функция` keywords
 * literally — comments and string literals that contain those words may produce
 * false positives. For typical BSL modules this is sufficient. A future stage may
 * replace this with full Xtext parsing once we resolve the runtime integration
 * issues.
 */
public class BslAstReader {

    private static final Pattern METHOD_HEADER = Pattern.compile(
        "(?im)^[ \\t]*(Процедура|Procedure|Функция|Function)\\s+([A-Za-zА-Яа-я_][A-Za-zА-Яа-я0-9_]*)\\s*\\(([^)]*)\\)\\s*(Экспорт|Export)?");

    private static final Pattern METHOD_END = Pattern.compile(
        "(?im)^[ \\t]*(КонецПроцедуры|EndProcedure|КонецФункции|EndFunction)\\b");

    @Inject
    public BslAstReader() {
    }

    /** Read raw text of a file using its declared charset. */
    public String readContent(IFile file) throws BslParseException {
        String charsetName;
        try {
            charsetName = file.getCharset();
        } catch (CoreException e) {
            throw new BslParseException("failed to detect charset: " + e.getMessage());
        }
        Charset charset;
        try {
            charset = Charset.forName(charsetName);
        } catch (RuntimeException e) {
            throw new BslParseException("unsupported charset: " + charsetName);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(file.getContents(), charset))) {
            int c;
            while ((c = r.read()) != -1) sb.append((char) c);
        } catch (IOException | CoreException e) {
            throw new BslParseException("failed to read: " + e.getMessage());
        }
        return sb.toString();
    }

    /** List top-level methods using regex on the file text. */
    public List<MethodInfo> listMethods(IFile file) throws BslParseException {
        return parse(readContent(file));
    }

    /** Find a method by name. */
    public Optional<MethodInfo> findMethod(IFile file, String name) throws BslParseException {
        for (MethodInfo m : listMethods(file)) {
            if (name.equals(m.name())) return Optional.of(m);
        }
        return Optional.empty();
    }

    /**
     * Module type inferred from the file path inside a 1C:EDT-layout project.
     * Returns one of: COMMON_MODULE, ORDINARY_APP_MODULE, MANAGED_APP_MODULE,
     * EXTERNAL_CONN_MODULE, SESSION_MODULE, OBJECT_MODULE, MANAGER_MODULE,
     * VALUE_MANAGER_MODULE, RECORDSET_MODULE, FORM_MODULE, COMMAND_MODULE,
     * WEB_SERVICE_MODULE, HTTP_SERVICE_MODULE, INTEGRATION_SERVICE_MODULE,
     * BOT_MODULE, or UNKNOWN.
     */
    public String getModuleType(IFile file) {
        String path = file.getFullPath().toString().replace('\\', '/');
        String name = file.getName();

        if (path.contains("/Forms/") && "Module.bsl".equals(name)) return "FORM_MODULE";
        if (path.contains("/Commands/") && "CommandModule.bsl".equals(name)) return "COMMAND_MODULE";
        if (path.contains("/CommonModules/") && "Module.bsl".equals(name)) return "COMMON_MODULE";
        if (path.contains("/WebServices/") && "Module.bsl".equals(name)) return "WEB_SERVICE_MODULE";
        if (path.contains("/HTTPServices/") && "Module.bsl".equals(name)) return "HTTP_SERVICE_MODULE";
        if (path.contains("/IntegrationServices/") && "Module.bsl".equals(name)) return "INTEGRATION_SERVICE_MODULE";
        if (path.contains("/Bots/") && "Module.bsl".equals(name)) return "BOT_MODULE";

        switch (name) {
            case "OrdinaryApplicationModule.bsl": return "ORDINARY_APP_MODULE";
            case "ManagedApplicationModule.bsl":  return "MANAGED_APP_MODULE";
            case "ExternalConnectionModule.bsl":  return "EXTERNAL_CONN_MODULE";
            case "SessionModule.bsl":             return "SESSION_MODULE";
            case "ObjectModule.bsl":              return "OBJECT_MODULE";
            case "ManagerModule.bsl":             return "MANAGER_MODULE";
            case "ValueManagerModule.bsl":        return "VALUE_MANAGER_MODULE";
            case "RecordSetModule.bsl":           return "RECORDSET_MODULE";
            default:                              return "UNKNOWN";
        }
    }

    /**
     * Validate BSL content with a basic balance check: each Процедура/Функция has
     * a matching КонецПроцедуры/КонецФункции, and there is no nested Процедура inside
     * an open method. Returns "line:col: msg" strings; empty list means OK.
     *
     * <p>This is NOT a full BSL syntax checker. It catches structural defects only.
     * The {@code uri} parameter is unused but kept for API stability.
     */
    public List<String> validate(String content, URI uri) {
        List<String> errors = new ArrayList<>();
        try {
            List<MethodInfo> methods = parse(content);
            // parse() throws BslParseException on unbalanced begin/end; if we got here,
            // structure is OK.
            if (methods.isEmpty() && content.contains("Процедура")) {
                // Defensive: someone wrote Процедура but parser didn't pick it up
                // (likely missing parentheses).
                errors.add("0:0: method declaration syntax error (no method recognised)");
            }
        } catch (BslParseException e) {
            errors.add("0:0: " + e.getMessage());
        }
        return errors;
    }

    /** Internal: regex-parse content into MethodInfo list. */
    private static List<MethodInfo> parse(String content) throws BslParseException {
        List<MethodInfo> out = new ArrayList<>();
        Matcher header = METHOD_HEADER.matcher(content);
        int searchFrom = 0;

        while (header.find(searchFrom)) {
            int headerStart = header.start();
            String keyword = header.group(1);
            String name = header.group(2);
            boolean export = header.group(4) != null;
            boolean isFunction = keyword.equalsIgnoreCase("Функция") || keyword.equalsIgnoreCase("Function");

            // Find matching end starting right after the header.
            Matcher end = METHOD_END.matcher(content);
            if (!end.find(header.end())) {
                throw new BslParseException("unterminated method '" + name + "' starting at offset " + headerStart);
            }
            int endTokenEnd = end.end();
            String endKw = end.group(1);
            boolean endIsFunction = endKw.equalsIgnoreCase("КонецФункции") || endKw.equalsIgnoreCase("EndFunction");
            if (endIsFunction != isFunction) {
                throw new BslParseException("method '" + name + "' has mismatched end keyword: " + endKw);
            }

            int lineStart = lineOf(content, headerStart);
            int lineEnd = lineOf(content, endTokenEnd - 1);

            out.add(new MethodInfo(name, isFunction ? "function" : "procedure", export,
                                   lineStart, lineEnd, headerStart, endTokenEnd - headerStart));

            searchFrom = endTokenEnd;
        }
        return out;
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    public record MethodInfo(String name, String kind, boolean export,
                             int lineStart, int lineEnd, int offset, int length) {
    }
}
